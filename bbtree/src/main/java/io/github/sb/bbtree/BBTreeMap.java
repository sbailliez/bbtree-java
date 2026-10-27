package io.github.sb.bbtree;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * A concurrent sorted map: a B+ tree with inline keys and optimistic lock coupling, after
 * <a href="https://github.com/XiangpengHao/bbtree">bbtree</a> by Xiangpeng Hao.
 *
 * <p><b>Search.</b> If you give the map an {@link InlineKey}, each node keeps a {@code long}
 * window of each key, taken right after the encoding prefix that all keys of the node share. The
 * prefix is the common prefix of the two fences of the node: the separators around it in its
 * parent. Thus a node keeps only the length of its prefix, not the prefix bytes. A node search
 * scans the sorted windows up to the first one that is not smaller than the window of the
 * target. The map reads a key object only when two windows tie. For keys of a fixed
 * length that the window covers, such as {@link Long}, a tie is a match and the map reads no key
 * object at all. For key types of a fixed length, the vector maps of bbtree-vector also keep the
 * first 4 bytes of each window in the inner nodes, and search them in the nodes with two fences.
 * Thus 64 separators take 256 bytes in place of 512, and a tie on 4 bytes compares the
 * separators.
 *
 * <p><b>Concurrency.</b> Each node has a version lock. A read writes no shared memory: it reads
 * the version of a node, reads the node, and validates the version. If a writer changed the node,
 * the read starts again from the root. A write locks only the leaf, plus the parent when the leaf
 * or an inner node splits. The garbage collector keeps every node alive while a reader holds it,
 * so the map needs no epoch and no free list.
 *
 * <p><b>Contract.</b> Keys and values must not be {@code null}. The comparator must be consistent
 * with {@code equals} and must have no side effects: an optimistic read can call it on a key that
 * a concurrent writer moves, and then discards the result. Iterators are weakly consistent, as in
 * {@link java.util.concurrent.ConcurrentSkipListMap}: they never throw {@link
 * java.util.ConcurrentModificationException}, they give the keys in strictly ascending order, and
 * they give each entry that was present for the full iteration. {@link #size()} is exact when no
 * write runs at the same time.
 *
 * <p><b>Removal.</b> A removal that empties a leaf removes the empty nodes on its path: an empty
 * leaf merges with a sibling, an empty inner node gives its only child to a sibling, and a root
 * with one child gives its place to the child. This is best effort under contention. The map does
 * not merge nodes that are not empty.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class BBTreeMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {

  /** The default number of entries in a leaf. */
  public static final int DEFAULT_LEAF_CAPACITY = 32;

  /** The default number of separators in an inner node. */
  public static final int DEFAULT_INNER_CAPACITY = 64;

  static final int MIN_CAPACITY = 4;
  static final int MAX_CAPACITY = 1024;

  /**
   * The length of each array of 4-byte inner windows is a multiple of this number: 64 windows,
   * the int lanes of the widest vector register that Java supports (2048-bit SVE).
   */
  static final int NARROW_ALIGNMENT = 64;

  /** The number of lookups that {@link #getMany} interleaves. */
  private static final int BATCH_LANES = 16;

  private final Comparator<? super K> comparator;
  private final InlineKey<K> inline;
  private final WindowScan scan;

  /** A node with a prefix of this length or more compares no key on a window tie. */
  private final int exactFrom;

  /** An inner node with 4-byte windows and a prefix of this length or more compares no key. */
  private final int narrowExactFrom;

  /** {@code true} if the leaves keep primitive {@code long} values ({@link Leaf#longValues}). */
  private final boolean primitiveValues;

  /**
   * {@code true} if the inner nodes also keep 4-byte windows ({@link Inner#narrow}). A node with
   * both fences searches them. The root and the nodes on the left and right borders have a prefix
   * of 0, so dense keys would tie on 4 bytes there: these nodes search the 8-byte windows.
   */
  private final boolean narrow;

  private final int leafCapacity;
  private final int innerCapacity;
  private final LongAdder size = new LongAdder();

  /** The root. It changes only while a writer holds the old root. */
  private volatile Node root;

  /** Makes an empty map that sorts its keys in their natural order, with no inline keys. */
  public BBTreeMap() {
    this(null, null, DEFAULT_LEAF_CAPACITY, DEFAULT_INNER_CAPACITY);
  }

  /**
   * Makes an empty map that sorts its keys with {@code comparator}, with no inline keys.
   *
   * @param comparator the order of the keys
   */
  public BBTreeMap(Comparator<? super K> comparator) {
    this(Objects.requireNonNull(comparator), null, DEFAULT_LEAF_CAPACITY, DEFAULT_INNER_CAPACITY);
  }

  /**
   * Makes an empty map that sorts its keys with {@code inlineKey} and searches its nodes on the
   * inline windows. This is the fast configuration.
   *
   * @param inlineKey the order and the encoding of the keys, for example {@link InlineKey#LONG}
   */
  public BBTreeMap(InlineKey<K> inlineKey) {
    this(inlineKey, DEFAULT_LEAF_CAPACITY, DEFAULT_INNER_CAPACITY);
  }

  /**
   * Makes an empty map with inline keys and the node capacities that you give.
   *
   * @param inlineKey the order and the encoding of the keys
   * @param leafCapacity the number of entries in a leaf, from 4 to 1024
   * @param innerCapacity the number of separators in an inner node, from 4 to 1024
   */
  public BBTreeMap(InlineKey<K> inlineKey, int leafCapacity, int innerCapacity) {
    this(Objects.requireNonNull(inlineKey), inlineKey, leafCapacity, innerCapacity);
  }

  BBTreeMap(
      Comparator<? super K> comparator, InlineKey<K> inline, int leafCapacity, int innerCapacity) {
    this(comparator, inline, leafCapacity, innerCapacity, WindowScan.SCALAR, false);
  }

  BBTreeMap(
      Comparator<? super K> comparator,
      InlineKey<K> inline,
      int leafCapacity,
      int innerCapacity,
      WindowScan scan,
      boolean narrowInnerWindows) {
    this(comparator, inline, leafCapacity, innerCapacity, scan, narrowInnerWindows, false);
  }

  @SuppressWarnings("unchecked")
  BBTreeMap(
      Comparator<? super K> comparator,
      InlineKey<K> inline,
      int leafCapacity,
      int innerCapacity,
      WindowScan scan,
      boolean narrowInnerWindows,
      boolean primitiveValues) {
    if (primitiveValues && inline != InlineKey.LONG) {
      throw new IllegalArgumentException("primitive values need InlineKey.LONG");
    }
    if (leafCapacity < MIN_CAPACITY || leafCapacity > MAX_CAPACITY) {
      throw new IllegalArgumentException("leafCapacity out of range: " + leafCapacity);
    }
    if (innerCapacity < MIN_CAPACITY || innerCapacity > MAX_CAPACITY) {
      throw new IllegalArgumentException("innerCapacity out of range: " + innerCapacity);
    }
    this.comparator =
        comparator != null ? comparator : (Comparator<? super K>) Comparator.naturalOrder();
    this.inline = inline;
    this.scan = Objects.requireNonNull(scan);
    int fixed = inline == null ? -1 : inline.fixedLength();
    this.exactFrom = fixed < 0 ? Integer.MAX_VALUE : fixed - Long.BYTES;
    this.narrowExactFrom = fixed < 0 ? Integer.MAX_VALUE : fixed - Integer.BYTES;
    this.narrow = inline != null && narrowInnerWindows;
    this.primitiveValues = primitiveValues;
    this.leafCapacity = leafCapacity;
    this.innerCapacity = innerCapacity;
    this.root = new Leaf(leafCapacity, inline != null, primitiveValues);
  }

  /**
   * Gives {@code true} if 4-byte inner windows suit this key encoding: the encodings of a fixed
   * length. Strings and byte arrays tie too often on 4 bytes. The scalar scan gets no gain from
   * 4-byte windows, so only the vector maps of bbtree-vector use them.
   */
  static boolean narrowSuits(InlineKey<?> inline) {
    return inline != null && inline.fixedLength() >= 0;
  }

  /**
   * Gives the comparator that orders the keys.
   *
   * @return the comparator, never {@code null}
   */
  public Comparator<? super K> comparator() {
    return comparator;
  }

  // ---------------------------------------------------------------------------------------------
  // Node search

  @SuppressWarnings("unchecked")
  private static <K> K key(Object key) {
    return (K) Objects.requireNonNull(key);
  }

  /** Gives the window of {@code key} in a node, with the sign bit flipped. */
  private long window(K key, int prefix) {
    return inline.window(key, prefix) ^ Long.MIN_VALUE;
  }

  /** Gives the number of windows to scan in a head: the count, clamped to the capacity. */
  private static int windowCount(long[] head) {
    return Math.min(Node.count(head), head.length - Node.W);
  }

  /**
   * Finds {@code key} in a leaf. The comparator runs in a separate method, so that this method
   * stays small enough for the JIT to inline the {@link InlineKey} call and the scan.
   *
   * @return the index of the key, or {@code -(insertion point) - 1} if the leaf does not have it
   */
  private int find(Node node, K key) {
    return find(node.head, node, key);
  }

  /**
   * Does {@link #find(Node, Object)} on the head of {@code node}. The search reads {@code node}
   * only when two windows tie, for its keys.
   */
  private int find(long[] head, Object node, K key) {
    if (head.length == Node.W) {
      Object[] keys = ((Node) node).keys;
      return findKey(keys, 0, Math.min(Node.count(head), keys.length), key);
    }
    int prefix = Node.prefix(head);
    long position = scan.scan(head, Node.W, windowCount(head), window(key, prefix));
    int lo = (int) (position >>> 32);
    int hi = (int) position;
    if (lo == hi) {
      return -lo - 1;
    }
    if (prefix >= exactFrom) {
      return lo;
    }
    return findKey(((Node) node).keys, lo, hi, key);
  }

  /** Searches {@code keys[lo, hi)} with the comparator. Gives the same result as {@link #find}. */
  @SuppressWarnings("unchecked")
  private int findKey(Object[] keys, int lo, int hi, K key) {
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      int c = comparator.compare((K) keys[mid], key);
      if (c < 0) {
        lo = mid + 1;
      } else if (c > 0) {
        hi = mid;
      } else {
        return mid;
      }
    }
    return -lo - 1;
  }

  /** Gives the index of the child of an inner node that holds {@code key}. */
  private int childIndex(Inner node, K key) {
    return childIndex(node.head, node, key);
  }

  /**
   * Does {@link #childIndex(Inner, Object)} on the head of {@code node}. The search reads {@code
   * node} only when two windows tie, and in a map with 4-byte inner windows.
   */
  private int childIndex(long[] head, Object node, K key) {
    int prefix = Node.prefix(head);
    if (narrow) {
      Inner inner = (Inner) node;
      int[] nw = inner.narrow;
      if (inner.low != null && inner.high != null) {
        long position =
            scan.scan(nw, Math.min(Node.count(head), nw.length), narrow(window(key, prefix)));
        int lo = (int) (position >>> 32);
        int hi = (int) position;
        if (lo == hi || prefix >= narrowExactFrom) {
          return hi;
        }
        return upperBound(inner.keys, lo, hi, key);
      }
    }
    if (head.length == Node.W) {
      Object[] keys = ((Node) node).keys;
      return upperBound(keys, 0, Math.min(Node.count(head), keys.length), key);
    }
    long position = scan.scan(head, Node.W, windowCount(head), window(key, prefix));
    int lo = (int) (position >>> 32);
    int hi = (int) position;
    if (lo == hi || prefix >= exactFrom) {
      return hi;
    }
    return upperBound(((Node) node).keys, lo, hi, key);
  }

  /**
   * Gives {@code lo} plus the number of keys in {@code keys[lo, hi)} that are not larger than
   * {@code key}. The keys before {@code lo} are smaller than {@code key}.
   */
  @SuppressWarnings("unchecked")
  private int upperBound(Object[] keys, int lo, int hi, K key) {
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (comparator.compare((K) keys[mid], key) <= 0) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /**
   * Gives the 4-byte window of an inner node: the first 4 bytes of the 8-byte window. The sign bit
   * of the 8-byte window is the sign bit of the result, so a signed compare still gives the
   * unsigned order.
   */
  static int narrow(long window) {
    return (int) (window >> 32);
  }

  /**
   * Gives the window of a primitive {@code long} key, as {@link InlineKey#LONG} gives it for the
   * boxed key, with the sign bit flipped.
   */
  private static long longWindow(long key, int prefix) {
    long encoded = key ^ Long.MIN_VALUE;
    return (prefix >= Long.BYTES ? 0L : encoded << (prefix << 3)) ^ Long.MIN_VALUE;
  }

  /**
   * Finds a primitive key in the leaf with this head, in a map with {@link InlineKey#LONG}. Equal
   * windows of that key type are equal keys, so this search reads no key object.
   */
  private int findLong(long[] head, long key) {
    long position =
        scan.scan(head, Node.W, windowCount(head), longWindow(key, Node.prefix(head)));
    int lo = (int) (position >>> 32);
    return lo == (int) position ? -lo - 1 : lo;
  }

  /** Gives the child of an inner node for a primitive key, as {@link #findLong} does. */
  private int childIndexLong(Inner node, long key) {
    return childIndexLong(node.head, node, key);
  }

  /** Does {@link #childIndexLong(Inner, long)} on the head of {@code node}. */
  private int childIndexLong(long[] head, Object node, long key) {
    int prefix = Node.prefix(head);
    if (narrow) {
      Inner inner = (Inner) node;
      int[] nw = inner.narrow;
      if (inner.low != null && inner.high != null) {
        long position =
            scan.scan(nw, Math.min(Node.count(head), nw.length), narrow(longWindow(key, prefix)));
        int lo = (int) (position >>> 32);
        int hi = (int) position;
        if (lo == hi || prefix >= narrowExactFrom) {
          return hi;
        }
        Object[] keys = inner.keys;
        while (lo < hi) {
          int mid = (lo + hi) >>> 1;
          if ((Long) keys[mid] <= key) {
            lo = mid + 1;
          } else {
            hi = mid;
          }
        }
        return lo;
      }
    }
    return (int) scan.scan(head, Node.W, windowCount(head), longWindow(key, prefix));
  }

  /** Gives value {@code pos} of a leaf body. A primitive value comes back as a {@link Long}. */
  private Object valueIn(Object body, int pos) {
    return primitiveValues ? Long.valueOf(((long[]) body)[pos]) : ((Object[]) body)[pos];
  }

  // ---------------------------------------------------------------------------------------------
  // Reads
  //
  // A lookup moves from a parent to a child through the triple of the child in the parent: the
  // child, its head and its body (see Inner). It reads the version, the count, the prefix and the
  // windows of the child from the head, and the next triple or the value from the body. It loads a
  // node object only for a tie of windows. A triple that a writer changes at the same time can be
  // torn, but the validation of the parent then fails, and the lookup starts again.

  @Override
  public V get(Object key) {
    K k = key(key);
    restart:
    while (true) {
      Node top = root;
      Object node = top;
      long[] head = top.head;
      Object body = top.body();
      long v = Node.version(head);
      if (Node.isLocked(v) || top != root) {
        Thread.onSpinWait();
        continue;
      }
      try {
        while (!Node.isLeaf(head)) {
          Object[] slots = (Object[]) body;
          int i = childIndex(head, node, k);
          Object child = Inner.childObject(slots, i);
          long[] childHead = Inner.childHead(slots, i);
          Object childBody = Inner.childBody(slots, i);
          long cv = Node.version(childHead);
          if (Node.isLocked(cv) || !Node.validate(head, v)) {
            Thread.onSpinWait();
            continue restart;
          }
          node = child;
          head = childHead;
          body = childBody;
          v = cv;
        }
        int pos = find(head, node, k);
        Object value = pos >= 0 ? valueIn(body, pos) : null;
        if (Node.validate(head, v)) {
          @SuppressWarnings("unchecked")
          V result = (V) value;
          return result;
        }
      } catch (RuntimeException e) {
        if (Node.validate(head, v)) {
          throw e;
        }
      }
    }
  }

  @Override
  public boolean containsKey(Object key) {
    return get(key) != null;
  }

  /** Gives true if the leaves keep primitive {@code long} values. */
  boolean hasPrimitiveValues() {
    return primitiveValues;
  }

  /** Gives true if the map uses {@link InlineKey#LONG}, which the primitive methods need. */
  boolean hasLongKeys() {
    return inline == InlineKey.LONG;
  }

  /**
   * Looks up a primitive key, as {@link #get} does for the boxed key, with no key object. Call
   * it only when {@link #hasLongKeys()} is true.
   */
  @SuppressWarnings("unchecked")
  V getLong(long key) {
    restart:
    while (true) {
      Node top = root;
      Object node = top;
      long[] head = top.head;
      Object body = top.body();
      long v = Node.version(head);
      if (Node.isLocked(v) || top != root) {
        Thread.onSpinWait();
        continue;
      }
      try {
        while (!Node.isLeaf(head)) {
          Object[] slots = (Object[]) body;
          int i = childIndexLong(head, node, key);
          Object child = Inner.childObject(slots, i);
          long[] childHead = Inner.childHead(slots, i);
          Object childBody = Inner.childBody(slots, i);
          long cv = Node.version(childHead);
          if (Node.isLocked(cv) || !Node.validate(head, v)) {
            Thread.onSpinWait();
            continue restart;
          }
          node = child;
          head = childHead;
          body = childBody;
          v = cv;
        }
        int pos = findLong(head, key);
        Object value = pos >= 0 ? valueIn(body, pos) : null;
        if (Node.validate(head, v)) {
          return (V) value;
        }
      } catch (RuntimeException e) {
        // A torn read can see a null child during a split.
        if (Node.validate(head, v)) {
          throw e;
        }
      }
    }
  }

  /**
   * Looks up the primitive value of a primitive key in a map of primitive values. It does the same
   * descent as {@link #getLong}, and reads the value with no boxing.
   *
   * @return the value, or {@code absent} if the map does not have the key
   */
  long getLongValue(long key, long absent) {
    restart:
    while (true) {
      Node top = root;
      Object node = top;
      long[] head = top.head;
      Object body = top.body();
      long v = Node.version(head);
      if (Node.isLocked(v) || top != root) {
        Thread.onSpinWait();
        continue;
      }
      try {
        while (!Node.isLeaf(head)) {
          Object[] slots = (Object[]) body;
          int i = childIndexLong(head, node, key);
          Object child = Inner.childObject(slots, i);
          long[] childHead = Inner.childHead(slots, i);
          Object childBody = Inner.childBody(slots, i);
          long cv = Node.version(childHead);
          if (Node.isLocked(cv) || !Node.validate(head, v)) {
            Thread.onSpinWait();
            continue restart;
          }
          node = child;
          head = childHead;
          body = childBody;
          v = cv;
        }
        int pos = findLong(head, key);
        long value = pos >= 0 ? ((long[]) body)[pos] : absent;
        if (Node.validate(head, v)) {
          return value;
        }
      } catch (RuntimeException e) {
        // A torn read can see a null child during a split.
        if (Node.validate(head, v)) {
          throw e;
        }
      }
    }
  }

  /**
   * Looks up many keys at the same time. The lookups go down the tree one level at a time,
   * together, so that the CPU waits for the cache misses of different lookups in parallel.
   *
   * <p>Java has no prefetch instruction, and an acquire load keeps later loads from starting
   * before it. Thus each level has three passes: a search of every lane with plain loads, a plain
   * load of the head of every child that starts its cache miss, and then the version reads and
   * validations, which find the heads in the cache. A lane that does not validate, or that meets
   * an exception, falls back to {@link #get}.
   *
   * @param keys the keys to look up, none {@code null}
   * @param results the array that receives the value of {@code keys[i]} at index {@code i}, or
   *     {@code null} if the map does not have the key; at least as long as {@code keys}
   */
  public void getMany(K[] keys, V[] results) {
    if (results.length < keys.length) {
      throw new IllegalArgumentException("results is shorter than keys");
    }
    for (K k : keys) {
      Objects.requireNonNull(k);
    }
    getMany(keys, null, keys.length, results, null, 0L);
  }

  /**
   * Looks up many primitive keys, as {@link #getMany} does. Call it only when {@link
   * #hasLongKeys()} is true.
   */
  void getManyLong(long[] keys, Object[] results) {
    if (results.length < keys.length) {
      throw new IllegalArgumentException("results is shorter than keys");
    }
    getMany(null, keys, keys.length, results, null, 0L);
  }

  /**
   * Looks up many primitive keys in a map of primitive values, as {@link #getMany} does, and
   * writes the primitive values to {@code results}, or {@code absent} for a missing key.
   */
  void getManyLongValues(long[] keys, long[] results, long absent) {
    if (results.length < keys.length) {
      throw new IllegalArgumentException("results is shorter than keys");
    }
    getMany(null, keys, keys.length, null, results, absent);
  }

  /**
   * The batch lookup of {@link #getMany}, {@link #getManyLong} and {@link #getManyLongValues}.
   * Exactly one of {@code keys} and {@code longKeys} is not {@code null}, and exactly one of
   * {@code results} and {@code longResults}.
   */
  private void getMany(
      Object[] keys,
      long[] longKeys,
      int length,
      Object[] results,
      long[] longResults,
      long absent) {
    int lanes = Math.min(BATCH_LANES, length);
    Node[] nodes = new Node[lanes];
    Node[] children = new Node[lanes];
    long[] versions = new long[lanes];
    // The sum of the loads that only start cache misses. The final check keeps the JIT from
    // removing them.
    long touched = 0;
    for (int start = 0; start < length; start += lanes) {
      int n = Math.min(lanes, length - start);
      if (keys != null && inline != null) {
        for (int j = 0; j < n; j++) {
          touched += inline.window(key(keys[start + j]), 0) & 1;
        }
      }
      Node top = root;
      long tv = top.version();
      if (Node.isLocked(tv) || top != root) {
        for (int j = 0; j < n; j++) {
          store(results, longResults, start + j, keys, longKeys, absent);
        }
        continue;
      }
      Arrays.fill(nodes, 0, n, top);
      Arrays.fill(versions, 0, n, tv);
      // All leaves are at the same depth, so the lanes reach the leaves together.
      boolean inner = top instanceof Inner;
      while (inner) {
        for (int j = 0; j < n; j++) {
          children[j] = childOf(nodes[j], keys, longKeys, start + j);
        }
        for (int j = 0; j < n; j++) {
          Node child = children[j];
          touched += child != null ? child.head[Node.META] & 1 : 0;
        }
        inner = false;
        for (int j = 0; j < n; j++) {
          Node parent = nodes[j];
          Node child = children[j];
          nodes[j] = null;
          if (child != null) {
            long cv = child.version();
            if (!Node.isLocked(cv) && parent.validate(versions[j])) {
              nodes[j] = child;
              versions[j] = cv;
              inner |= child instanceof Inner;
            }
          }
        }
      }
      for (int j = 0; j < n; j++) {
        Node node = nodes[j];
        int pos = node instanceof Leaf ? positionIn(node.head, node, keys, longKeys, start + j) : -1;
        if (pos == POSITION_FAILED) {
          nodes[j] = null;
          pos = -1;
        }
        if (longResults != null) {
          longResults[start + j] = pos >= 0 ? ((Leaf) node).longValues[pos] : absent;
        } else {
          results[start + j] = pos >= 0 ? valueAt((Leaf) node, pos) : null;
        }
      }
      for (int j = 0; j < n; j++) {
        if (!(nodes[j] instanceof Leaf leaf) || !leaf.validate(versions[j])) {
          store(results, longResults, start + j, keys, longKeys, absent);
        }
      }
    }
    if (touched < 0) {
      throw new AssertionError(touched);
    }
  }

  /** Looks up key {@code i} of a batch alone, and stores its value at index {@code i}. */
  private void store(
      Object[] results, long[] longResults, int i, Object[] keys, long[] longKeys, long absent) {
    if (longResults != null) {
      longResults[i] = getLongValue(longKeys[i], absent);
    } else {
      results[i] = keys != null ? get(keys[i]) : getLong(longKeys[i]);
    }
  }

  /**
   * Gives the child of an inner node for key {@code i} of a batch, or {@code null} if the read
   * failed.
   */
  @SuppressWarnings("unchecked")
  private Node childOf(Node node, Object[] keys, long[] longKeys, int i) {
    if (!(node instanceof Inner inner)) {
      return null;
    }
    try {
      int child =
          keys != null
              ? childIndex(inner.head, inner, (K) keys[i])
              : childIndexLong(inner.head, inner, longKeys[i]);
      return inner.child(child);
    } catch (RuntimeException e) {
      // The lookup alone repeats the read, and throws if the exception was not a stale read.
      return null;
    }
  }

  /** The result of {@link #positionIn} when the read threw. */
  private static final int POSITION_FAILED = Integer.MIN_VALUE;

  /**
   * Finds key {@code i} of a batch in a leaf, as {@link #find} does, or gives a negative number if
   * the head is not a leaf. Gives {@link #POSITION_FAILED} if the read threw: the lane then falls
   * back to the lookup alone, which throws if the exception was not a stale read.
   */
  @SuppressWarnings("unchecked")
  private int positionIn(long[] head, Object node, Object[] keys, long[] longKeys, int i) {
    if (head == null || !Node.isLeaf(head)) {
      return -1;
    }
    try {
      return keys != null ? find(head, node, (K) keys[i]) : findLong(head, longKeys[i]);
    } catch (RuntimeException e) {
      return POSITION_FAILED;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Writes

  /**
   * Locks and gives the leaf that holds {@code key}. The caller must unlock it.
   *
   * <p>If {@code forInsert} is {@code true}, the leaf has room for one more entry or already has
   * the key. Full nodes on the way split first. If {@code forInsert} is {@code false}, the leaf
   * has the key: if it does not, the method gives {@code null} and locks nothing, so that an
   * update of an absent key writes no shared memory.
   *
   * <p>An exception from the comparator or the {@link InlineKey} goes to the caller, with no lock
   * held. An exception during an optimistic read that did not validate is a stale read, and the
   * descent starts again.
   */
  private Leaf lockLeaf(K key, boolean forInsert) {
    restart:
    while (true) {
      Node node = root;
      long v = node.version();
      if (Node.isLocked(v) || node != root) {
        Thread.onSpinWait();
        continue;
      }
      Inner parent = null;
      long pv = 0;
      int slot = 0;
      try {
        while (node instanceof Inner inner) {
          if (forInsert && inner.count() >= inner.capacity()) {
            // Split a full inner node on the way down, so that a split below always has room
            // in its parent. The split runs outside this try block.
            break;
          }
          int i = childIndex(inner, key);
          Node child = inner.child(i);
          long cv = child.version();
          if (Node.isLocked(cv) || !inner.validate(v)) {
            Thread.onSpinWait();
            continue restart;
          }
          parent = inner;
          pv = v;
          slot = i;
          node = child;
          v = cv;
        }
        if (node instanceof Leaf leaf) {
          boolean needsRoom = forInsert && leaf.count() >= leaf.capacity();
          if (needsRoom || !forInsert) {
            boolean present = find(leaf, key) >= 0;
            if (!leaf.validate(v)) {
              continue;
            }
            if (!forInsert && !present) {
              return null;
            }
            needsRoom &= !present;
          }
          if (!needsRoom) {
            if (leaf.tryUpgrade(v)) {
              return leaf;
            }
            Thread.onSpinWait();
            continue;
          }
        }
      } catch (RuntimeException e) {
        if (node.validate(v)) {
          throw e;
        }
        continue;
      }
      trySplit(parent, pv, slot, node, v, key);
    }
  }

  /**
   * Splits {@code node} for the insert of {@code key}, if neither the node nor its parent changed
   * since the versions that the caller read. Does nothing if one of them changed: the caller
   * starts again from the root. An exception leaves the tree as it was, with no lock held.
   */
  private void trySplit(Inner parent, long pv, int slot, Node node, long v, K key) {
    if (parent == null) {
      if (!node.tryUpgrade(v)) {
        return;
      }
      boolean split = false;
      try {
        if (root != node) {
          return;
        }
        Split s = prepareSplit(node, key, 0);
        Inner top = new Inner(innerCapacity, inline != null, narrow);
        s.commit();
        split = true;
        top.setChild(0, node);
        top.setChild(1, s.right);
        top.keys[0] = s.separator;
        setWindow(top, 0, s.separatorWindow);
        top.setCount(1);
        root = top;
      } finally {
        unlock(node, split);
      }
      return;
    }
    if (!parent.tryUpgrade(pv)) {
      return;
    }
    boolean split = false;
    try {
      if (!node.tryUpgrade(v)) {
        return;
      }
      try {
        Split s = prepareSplit(node, key, parent.prefix());
        s.commit();
        split = true;
        insertChild(parent, slot, s.separator, s.separatorWindow, s.right);
      } finally {
        unlock(node, split);
      }
    } finally {
      unlock(parent, split);
    }
  }

  /**
   * Unlocks a node. A node that did not change keeps its old version, so that its readers do not
   * start again.
   */
  private static void unlock(Node node, boolean changed) {
    if (changed) {
      node.unlock();
    } else {
      node.unlockUnchanged();
    }
  }

  /**
   * A split that is ready to apply. {@link #prepareSplit} runs all user code and all allocations,
   * and changes nothing that another thread can see. {@link #commit} only moves references and
   * cannot throw, so a failure never leaves a half-split node.
   */
  private final class Split {
    final Node left;
    final Node right;
    final int mid;
    final Object separator;
    final long separatorWindow;
    final int leftPrefix;

    /** The new windows of the left node, or {@code null} if its prefix does not change. */
    final long[] leftWindows;

    Split(Node left, Node right, int mid, Object separator, long separatorWindow) {
      this.left = left;
      this.right = right;
      this.mid = mid;
      this.separator = separator;
      this.separatorWindow = separatorWindow;
      this.leftPrefix = prefixOf(left.low, separator);
      this.leftWindows =
          leftPrefix == left.prefix() ? null : windowsAfterSplit(left, 0, mid, leftPrefix);
    }

    void commit() {
      int n = left.count();
      if (left instanceof Leaf leaf) {
        Arrays.fill(leaf.keys, mid, n, null);
        if (leaf.values != null) {
          Arrays.fill(leaf.values, mid, n, null);
        }
        leaf.next = (Leaf) right;
      } else {
        Inner inner = (Inner) left;
        Arrays.fill(inner.keys, mid, n, null);
        inner.clearChildren(mid + 1, n + 1);
        if (inner.narrow != null) {
          Arrays.fill(inner.narrow, mid, n, Integer.MAX_VALUE);
        }
      }
      left.setCount(mid);
      left.high = separator;
      setPrefix(left, leftPrefix, leftWindows);
    }
  }

  /**
   * Prepares the split of a locked, full node. The new right node takes the upper half. If
   * {@code key} goes after the last key of the last node of its level, the keys arrive in
   * ascending order, as time-ordered IDs do. Then the split moves only the last key, and leaves
   * the left node full.
   *
   * @param parentPrefix the prefix of the node that receives the separator
   */
  @SuppressWarnings("unchecked")
  private Split prepareSplit(Node node, K key, int parentPrefix) {
    int n = node.count();
    boolean append = node.high == null && comparator.compare(key, (K) node.keys[n - 1]) > 0;
    Node right;
    int mid;
    Object separator;
    if (node instanceof Leaf leaf) {
      mid = append ? n - 1 : n >>> 1;
      Leaf r = new Leaf(leafCapacity, inline != null, primitiveValues);
      System.arraycopy(leaf.keys, mid, r.keys, 0, n - mid);
      copyValues(leaf, mid, r, 0, n - mid);
      r.setCount(n - mid);
      r.next = leaf.next;
      separator = r.keys[0];
      right = r;
    } else {
      Inner inner = (Inner) node;
      mid = append ? n - 2 : n >>> 1;
      Inner r = new Inner(innerCapacity, inline != null, narrow);
      System.arraycopy(inner.keys, mid + 1, r.keys, 0, n - mid - 1);
      Inner.copyChildren(inner, mid + 1, r, 0, n - mid);
      r.setCount(n - mid - 1);
      separator = inner.keys[mid];
      right = r;
    }
    right.low = separator;
    right.high = node.high;
    int rightPrefix = prefixOf(right.low, right.high);
    int from = node instanceof Leaf ? mid : mid + 1;
    setPrefix(right, rightPrefix, windowsAfterSplit(node, from, right.count(), rightPrefix));
    long separatorWindow = inline != null ? window((K) separator, parentPrefix) : 0L;
    return new Split(node, right, mid, separator, separatorWindow);
  }

  /** Gives the prefix of a node with these fences. A node open on one side has no prefix. */
  @SuppressWarnings("unchecked")
  private int prefixOf(Object low, Object high) {
    return inline == null || low == null || high == null
        ? 0
        : inline.commonPrefix((K) low, (K) high);
  }

  /**
   * Gives the windows of {@code node.keys[from, from + n)} at a new prefix, from index 0, for a
   * half of a split. A split never makes a prefix shorter. If the prefix does not change, the new
   * windows are a copy of the old ones. If the old windows hold the whole rest of each key, as for
   * fixed-length keys such as {@link Long}, the new windows are the old ones shifted. In these two
   * cases no key object is read. Otherwise the windows come from the keys.
   */
  private long[] windowsAfterSplit(Node node, int from, int n, int prefix) {
    if (inline == null) {
      return null;
    }
    boolean same = prefix == node.prefix();
    if (!same && (node.prefix() < exactFrom || prefix < node.prefix())) {
      return windowsOf(node.keys, from, n, prefix);
    }
    if (same) {
      return Arrays.copyOfRange(node.head, Node.W + from, Node.W + from + n);
    }
    int bits = (prefix - node.prefix()) << 3;
    long[] windows = new long[node.capacity()];
    for (int i = 0; i < n; i++) {
      long w = node.head[Node.W + from + i] ^ Long.MIN_VALUE;
      windows[i] = (bits >= Long.SIZE ? 0L : w << bits) ^ Long.MIN_VALUE;
    }
    return windows;
  }

  /** Computes the windows of the first {@code n} keys, or gives {@code null} without inline keys. */
  private long[] windowsOf(Object[] keys, int n, int prefix) {
    return windowsOf(keys, 0, n, prefix);
  }

  /**
   * Computes the windows of {@code keys[from, from + n)} from index 0, or gives {@code null}
   * without inline keys.
   */
  @SuppressWarnings("unchecked")
  private long[] windowsOf(Object[] keys, int from, int n, int prefix) {
    if (inline == null) {
      return null;
    }
    long[] windows = new long[keys.length];
    for (int i = 0; i < n; i++) {
      windows[i] = window((K) keys[from + i], prefix);
    }
    return windows;
  }

  /**
   * Sets the prefix of a locked node, and its first {@code count} windows if {@code windows} is
   * not {@code null}. An inner node with 4-byte windows also keeps the first 4 bytes of each.
   */
  private static void setPrefix(Node node, int prefix, long[] windows) {
    node.setPrefixLength(prefix);
    if (windows == null) {
      return;
    }
    System.arraycopy(windows, 0, node.head, Node.W, node.count());
    if (node instanceof Inner inner && inner.narrow != null) {
      for (int i = 0; i < node.count(); i++) {
        inner.narrow[i] = narrow(windows[i]);
      }
    }
  }

  /** Sets window {@code i} of a locked node, if the node has windows. */
  private static void setWindow(Node node, int i, long window) {
    if (!node.hasWindows()) {
      return;
    }
    node.head[Node.W + i] = window;
    if (node instanceof Inner inner && inner.narrow != null) {
      inner.narrow[i] = narrow(window);
    }
  }

  /** Puts {@code separator} at index {@code slot} of a locked inner node, with {@code right}. */
  private static void insertChild(
      Inner node, int slot, Object separator, long separatorWindow, Node right) {
    int n = node.count();
    System.arraycopy(node.keys, slot, node.keys, slot + 1, n - slot);
    Inner.copyChildren(node, slot + 1, node, slot + 2, n - slot);
    node.keys[slot] = separator;
    node.setChild(slot + 1, right);
    if (node.hasWindows()) {
      System.arraycopy(node.head, Node.W + slot, node.head, Node.W + slot + 1, n - slot);
      node.head[Node.W + slot] = separatorWindow;
    }
    if (node.narrow != null) {
      System.arraycopy(node.narrow, slot, node.narrow, slot + 1, n - slot);
      node.narrow[slot] = narrow(separatorWindow);
    }
    node.setCount(n + 1);
  }

  /** Removes separator {@code keyIndex} and child {@code childIndex} from a locked inner node. */
  private static void removeChild(Inner node, int keyIndex, int childIndex) {
    int n = node.count();
    System.arraycopy(node.keys, keyIndex + 1, node.keys, keyIndex, n - keyIndex - 1);
    Inner.copyChildren(node, childIndex + 1, node, childIndex, n - childIndex);
    if (node.hasWindows()) {
      System.arraycopy(node.head, Node.W + keyIndex + 1, node.head, Node.W + keyIndex, n - keyIndex - 1);
    }
    if (node.narrow != null) {
      System.arraycopy(node.narrow, keyIndex + 1, node.narrow, keyIndex, n - keyIndex - 1);
      node.narrow[n - 1] = Integer.MAX_VALUE;
    }
    node.keys[n - 1] = null;
    node.setChild(n, null);
    node.setCount(n - 1);
  }

  /** Puts an entry at index {@code pos} of a locked leaf that has room. */
  private void insertAt(Leaf leaf, int pos, K key, V value) {
    insertAt(leaf, pos, key, value, 0L);
  }

  /**
   * Puts an entry at index {@code pos} of a locked leaf that has room. A map of primitive values
   * stores {@code longValue}, and the others store {@code value}.
   */
  private void insertAt(Leaf leaf, int pos, K key, Object value, long longValue) {
    int n = leaf.count();
    long w = inline != null ? window(key, leaf.prefix()) : 0L;
    System.arraycopy(leaf.keys, pos, leaf.keys, pos + 1, n - pos);
    copyValues(leaf, pos, leaf, pos + 1, n - pos);
    leaf.keys[pos] = key;
    if (leaf.values != null) {
      leaf.values[pos] = value;
    } else {
      leaf.longValues[pos] = longValue;
    }
    if (inline != null) {
      System.arraycopy(leaf.head, Node.W + pos, leaf.head, Node.W + pos + 1, n - pos);
      leaf.head[Node.W + pos] = w;
    }
    leaf.setCount(n + 1);
  }

  /** Removes the entry at index {@code pos} of a locked leaf. */
  private static void removeAt(Leaf leaf, int pos) {
    int n = leaf.count();
    int tail = n - pos - 1;
    System.arraycopy(leaf.keys, pos + 1, leaf.keys, pos, tail);
    copyValues(leaf, pos + 1, leaf, pos, tail);
    if (leaf.hasWindows()) {
      System.arraycopy(leaf.head, Node.W + pos + 1, leaf.head, Node.W + pos, tail);
    }
    leaf.keys[n - 1] = null;
    if (leaf.values != null) {
      leaf.values[n - 1] = null;
    }
    leaf.setCount(n - 1);
  }

  /** Gives value {@code pos} of a leaf. A primitive value comes back as a {@link Long}. */
  private static Object valueAt(Leaf leaf, int pos) {
    Object[] values = leaf.values;
    return values != null ? values[pos] : Long.valueOf(leaf.longValues[pos]);
  }

  /** Sets value {@code pos} of a locked leaf. A map of primitive values takes a {@link Long}. */
  private static void setValueAt(Leaf leaf, int pos, Object value) {
    if (leaf.values != null) {
      leaf.values[pos] = value;
    } else {
      leaf.longValues[pos] = (Long) value;
    }
  }

  /** Copies values {@code [from, from + n)} of {@code src} to index {@code to} of {@code dst}. */
  private static void copyValues(Leaf src, int from, Leaf dst, int to, int n) {
    if (src.values != null) {
      System.arraycopy(src.values, from, dst.values, to, n);
    } else {
      System.arraycopy(src.longValues, from, dst.longValues, to, n);
    }
  }

  private V put(K key, V value, boolean onlyIfAbsent) {
    Objects.requireNonNull(value);
    Leaf leaf = lockLeaf(key, true);
    try {
      if (leaf.count() == 0) {
        // As in TreeMap: the first key of an empty leaf meets the comparator too, so that a key
        // of a wrong type fails now and not at the next put.
        comparator.compare(key, key);
      }
      int pos = find(leaf, key);
      if (pos >= 0) {
        @SuppressWarnings("unchecked")
        V old = (V) valueAt(leaf, pos);
        if (!onlyIfAbsent) {
          setValueAt(leaf, pos, value);
        }
        return old;
      }
      insertAt(leaf, -pos - 1, key, value);
    } finally {
      leaf.unlock();
    }
    size.increment();
    return null;
  }

  @Override
  public V put(K key, V value) {
    return put(key(key), value, false);
  }

  /**
   * Sets the primitive value of a primitive key, with no boxed value, in a map of primitive values.
   *
   * @return the old value, or {@code absent} if the map did not have the key
   */
  @SuppressWarnings("unchecked")
  long putLongValue(long key, long value, long absent, boolean onlyIfAbsent) {
    K boxed = (K) Long.valueOf(key);
    Leaf leaf = lockLeaf(boxed, true);
    try {
      int pos = find(leaf, boxed);
      if (pos >= 0) {
        long old = leaf.longValues[pos];
        if (!onlyIfAbsent) {
          leaf.longValues[pos] = value;
        }
        return old;
      }
      insertAt(leaf, -pos - 1, boxed, null, value);
    } finally {
      leaf.unlock();
    }
    size.increment();
    return absent;
  }

  @Override
  public V putIfAbsent(K key, V value) {
    return put(key(key), value, true);
  }

  /**
   * Removes the entry of {@code key} if its value is {@code expected}, or if {@code expected} is
   * {@code null}. Gives the old value, or {@code null} if the map did not remove an entry.
   */
  private V doRemove(K key, Object expected) {
    Leaf leaf = lockLeaf(key, false);
    if (leaf == null) {
      return null;
    }
    V old;
    boolean empty;
    try {
      int pos = find(leaf, key);
      if (pos < 0) {
        return null;
      }
      @SuppressWarnings("unchecked")
      V value = (V) valueAt(leaf, pos);
      if (expected != null && !expected.equals(value)) {
        return null;
      }
      old = value;
      removeAt(leaf, pos);
      empty = leaf.count() == 0;
    } finally {
      leaf.unlock();
    }
    size.decrement();
    if (empty) {
      try {
        compact(key);
      } catch (RuntimeException e) {
        // The removal is done. Compaction is optional, and it changes nothing when user code
        // throws in it, so the removal does not fail: the empty node stays in the tree.
      }
    }
    return old;
  }

  @Override
  public V remove(Object key) {
    return doRemove(key(key), null);
  }

  @Override
  public boolean remove(Object key, Object value) {
    K k = key(key);
    return value != null && doRemove(k, value) != null;
  }

  @Override
  public V replace(K key, V value) {
    Objects.requireNonNull(value);
    Leaf leaf = lockLeaf(key(key), false);
    if (leaf == null) {
      return null;
    }
    try {
      int pos = find(leaf, key);
      if (pos < 0) {
        return null;
      }
      @SuppressWarnings("unchecked")
      V old = (V) valueAt(leaf, pos);
      setValueAt(leaf, pos, value);
      return old;
    } finally {
      leaf.unlock();
    }
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    Objects.requireNonNull(oldValue);
    Objects.requireNonNull(newValue);
    Leaf leaf = lockLeaf(key(key), false);
    if (leaf == null) {
      return false;
    }
    try {
      int pos = find(leaf, key);
      if (pos < 0 || !oldValue.equals(valueAt(leaf, pos))) {
        return false;
      }
      setValueAt(leaf, pos, newValue);
      return true;
    } finally {
      leaf.unlock();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Compaction

  /** The most passes of {@link #compact} after one removal. */
  private static final int COMPACT_PASSES = 64;

  /**
   * Removes the empty nodes on the path of {@code key}, after a removal emptied a leaf. Each pass
   * goes down the path and removes the first empty node that has a sibling in the same parent,
   * or makes the tree lower if the root has one child. An empty leaf merges with its sibling. An
   * empty inner node gives its only child to its sibling. The removed node stays locked for
   * ever, so that every reader that still holds it starts again.
   *
   * <p>This is best effort: if a node changes during a pass, the pass stops, and the empty node
   * stays until a later removal on its path. The map does not merge nodes that are not empty.
   */
  private void compact(K key) {
    for (int pass = 0; pass < COMPACT_PASSES; pass++) {
      if (!compactOnce(key)) {
        return;
      }
    }
  }

  /** Does one pass of {@link #compact}. Gives {@code true} if it removed a node. */
  private boolean compactOnce(K key) {
    Node node = root;
    long v = node.version();
    if (Node.isLocked(v) || node != root) {
      return false;
    }
    Node toLower = null;
    Inner parent = null;
    long pv = 0;
    int slot = 0;
    long cv = 0;
    try {
      if (node instanceof Inner top && top.count() == 0) {
        toLower = top;
      } else {
        while (node instanceof Inner inner) {
          int i = childIndex(inner, key);
          Node child = inner.child(i);
          cv = child.version();
          if (Node.isLocked(cv) || !inner.validate(v)) {
            return false;
          }
          if (child.count() == 0 && inner.count() > 0) {
            parent = inner;
            pv = v;
            slot = i;
            node = child;
            break;
          }
          node = child;
          v = cv;
        }
      }
      if (toLower == null && parent == null) {
        return false;
      }
    } catch (RuntimeException e) {
      if (node.validate(v)) {
        throw e;
      }
      return false;
    }
    if (toLower != null) {
      return lowerRoot(toLower, v);
    }
    return removeEmpty(parent, pv, slot, node, cv);
  }

  /** Makes the only child of the root the new root. The old root stays locked. */
  private boolean lowerRoot(Node top, long v) {
    if (!top.tryUpgrade(v)) {
      return false;
    }
    if (root != top || top.count() != 0) {
      top.unlockUnchanged();
      return false;
    }
    root = ((Inner) top).child(0);
    return true;
  }

  /**
   * Removes the empty child {@code slot} of {@code parent}, if no node changed since the versions
   * that the caller read. The parent has at least one separator, so the child has a sibling.
   */
  private boolean removeEmpty(Inner parent, long pv, int slot, Node empty, long ev) {
    if (!parent.tryUpgrade(pv)) {
      return false;
    }
    boolean removed = false;
    try {
      // The parent is locked, so its children do not change.
      boolean fromLeft = slot > 0;
      Node sibling = parent.child(fromLeft ? slot - 1 : slot + 1);
      long sv = sibling.version();
      // An empty inner node next to a full sibling takes a child from it, and both stay.
      boolean steal = empty instanceof Inner && sibling.count() >= sibling.capacity();
      // Lock the two children from left to right. An empty leaf on the left takes the entries
      // of its sibling, so that the leaf before it in the chain keeps a valid next link.
      Node first = fromLeft ? sibling : empty;
      Node second = fromLeft ? empty : sibling;
      Node gone = steal ? null : empty instanceof Leaf && !fromLeft ? sibling : empty;
      if (!first.tryUpgrade(fromLeft ? sv : ev)) {
        return false;
      }
      if (!second.tryUpgrade(fromLeft ? ev : sv)) {
        first.unlockUnchanged();
        return false;
      }
      try {
        if (steal) {
          stealChild((Inner) empty, (Inner) sibling, parent, slot, fromLeft);
        } else if (empty instanceof Leaf) {
          mergeIntoLeft((Leaf) first, (Leaf) second, parent, fromLeft ? slot : slot + 1);
        } else if (fromLeft) {
          giveChildLeft((Inner) sibling, (Inner) empty, parent, slot);
        } else {
          giveChildRight((Inner) empty, (Inner) sibling, parent);
        }
        removed = true;
        return true;
      } finally {
        // The node that left the tree stays locked.
        if (first != gone || !removed) {
          unlock(first, removed);
        }
        if (second != gone || !removed) {
          unlock(second, removed);
        }
      }
    } finally {
      unlock(parent, removed);
    }
  }

  /**
   * Moves the entries of a locked leaf into its locked left neighbor, which is empty or which
   * receives an empty leaf, and removes child {@code slot} of the parent.
   */
  private void mergeIntoLeft(Leaf left, Leaf right, Inner parent, int slot) {
    int n = left.count();
    int m = right.count();
    int prefix = prefixOf(left.low, right.high);
    long[] windows = null;
    if (inline != null) {
      windows = new long[left.capacity()];
      Object[] keys = new Object[n + m];
      System.arraycopy(left.keys, 0, keys, 0, n);
      System.arraycopy(right.keys, 0, keys, n, m);
      long[] w = windowsOf(keys, n + m, prefix);
      System.arraycopy(w, 0, windows, 0, n + m);
    }
    // Commit: nothing below throws.
    System.arraycopy(right.keys, 0, left.keys, n, m);
    copyValues(right, 0, left, n, m);
    left.setCount(n + m);
    left.high = right.high;
    left.next = right.next;
    setPrefix(left, prefix, windows);
    removeChild(parent, slot - 1, slot);
  }

  /**
   * Moves the nearest child of a locked, full sibling into a locked, empty inner node, with the
   * separator between them. The separator in the parent changes to the key that bounded the
   * moved child in the sibling. The fences of the moved child do not change.
   */
  @SuppressWarnings("unchecked")
  private void stealChild(Inner empty, Inner sibling, Inner parent, int slot, boolean fromLeft) {
    int n = sibling.count();
    int keyIndex = fromLeft ? slot - 1 : slot;
    Object oldSeparator = parent.keys[keyIndex];
    Object newSeparator = fromLeft ? sibling.keys[n - 1] : sibling.keys[0];
    Object emptyLow = fromLeft ? newSeparator : empty.low;
    Object emptyHigh = fromLeft ? empty.high : newSeparator;
    Object siblingLow = fromLeft ? sibling.low : newSeparator;
    Object siblingHigh = fromLeft ? newSeparator : sibling.high;
    Object[] siblingKeys = new Object[sibling.capacity()];
    if (fromLeft) {
      System.arraycopy(sibling.keys, 0, siblingKeys, 0, n - 1);
    } else {
      System.arraycopy(sibling.keys, 1, siblingKeys, 0, n - 1);
    }
    int emptyPrefix = prefixOf(emptyLow, emptyHigh);
    int siblingPrefix = prefixOf(siblingLow, siblingHigh);
    long[] emptyWindows = windowsOf(new Object[] {oldSeparator}, 1, emptyPrefix);
    long[] siblingWindows = windowsOf(siblingKeys, n - 1, siblingPrefix);
    long separatorWindow = inline != null ? window((K) newSeparator, parent.prefix()) : 0L;
    // Commit: nothing below throws.
    Node child = empty.child(0);
    empty.keys[0] = oldSeparator;
    if (fromLeft) {
      empty.setChild(0, sibling.child(n));
      empty.setChild(1, child);
      sibling.setChild(n, null);
    } else {
      empty.setChild(0, child);
      empty.setChild(1, sibling.child(0));
      Inner.copyChildren(sibling, 1, sibling, 0, n);
      sibling.setChild(n, null);
    }
    System.arraycopy(siblingKeys, 0, sibling.keys, 0, n);
    empty.setCount(1);
    sibling.setCount(n - 1);
    empty.low = emptyLow;
    empty.high = emptyHigh;
    sibling.low = siblingLow;
    sibling.high = siblingHigh;
    if (emptyWindows != null) {
      setWindow(empty, 0, emptyWindows[0]);
    }
    empty.setPrefixLength(emptyPrefix);
    setPrefix(sibling, siblingPrefix, siblingWindows);
    if (sibling.narrow != null) {
      sibling.narrow[n - 1] = Integer.MAX_VALUE;
    }
    parent.keys[keyIndex] = newSeparator;
    setWindow(parent, keyIndex, separatorWindow);
  }

  /** Gives the only child of a locked, empty inner node to its locked left neighbor. */
  private void giveChildLeft(Inner left, Inner empty, Inner parent, int slot) {
    Object separator = parent.keys[slot - 1];
    int n = left.count();
    Object[] keys = Arrays.copyOf(left.keys, left.capacity());
    keys[n] = separator;
    int prefix = prefixOf(left.low, empty.high);
    long[] windows = windowsOf(keys, n + 1, prefix);
    // Commit: nothing below throws.
    left.keys[n] = separator;
    left.setChild(n + 1, empty.child(0));
    left.setCount(n + 1);
    left.high = empty.high;
    setPrefix(left, prefix, windows);
    removeChild(parent, slot - 1, slot);
  }

  /** Gives the only child of a locked, empty inner node (child 0) to its locked right neighbor. */
  private void giveChildRight(Inner empty, Inner right, Inner parent) {
    Object separator = parent.keys[0];
    int n = right.count();
    Object[] keys = new Object[right.capacity()];
    keys[0] = separator;
    System.arraycopy(right.keys, 0, keys, 1, n);
    int prefix = prefixOf(empty.low, right.high);
    long[] windows = windowsOf(keys, n + 1, prefix);
    // Commit: nothing below throws.
    System.arraycopy(right.keys, 0, right.keys, 1, n);
    Inner.copyChildren(right, 0, right, 1, n + 1);
    right.keys[0] = separator;
    right.setChild(0, empty.child(0));
    right.setCount(n + 1);
    right.low = empty.low;
    setPrefix(right, prefix, windows);
    removeChild(parent, 0, 0);
  }

  // ---------------------------------------------------------------------------------------------
  // Size and bulk operations

  @Override
  public int size() {
    long n = size.sum();
    return n <= 0 ? 0 : (int) Math.min(n, Integer.MAX_VALUE);
  }

  @Override
  public boolean isEmpty() {
    return !new Cursor(null, true, null, false).hasNext();
  }

  /** Removes the entries one at a time. Entries that other threads put at the same time can stay. */
  @Override
  public void clear() {
    Cursor cursor = new Cursor(null, true, null, false);
    while (cursor.hasNext()) {
      cursor.advance();
      remove(cursor.key);
    }
  }

  /**
   * Gives the smallest key.
   *
   * @return the smallest key
   * @throws NoSuchElementException if the map is empty
   */
  public K firstKey() {
    Cursor cursor = new Cursor(null, true, null, false);
    if (!cursor.hasNext()) {
      throw new NoSuchElementException();
    }
    cursor.advance();
    return cursor.key;
  }

  /**
   * Gives the smallest key that is equal to or larger than {@code key}.
   *
   * @param key the lower bound
   * @return the key, or {@code null} if there is none
   */
  public K ceilingKey(K key) {
    Cursor cursor = new Cursor(key(key), true, null, false);
    if (!cursor.hasNext()) {
      return null;
    }
    cursor.advance();
    return cursor.key;
  }

  @Override
  public void forEach(BiConsumer<? super K, ? super V> action) {
    forEachInRange(null, null, action);
  }

  /**
   * Gives each entry with a key in {@code [from, to)} to {@code action}, in key order.
   *
   * @param from the inclusive lower bound, or {@code null} for no bound
   * @param to the exclusive upper bound, or {@code null} for no bound
   * @param action the action
   */
  public void forEachInRange(K from, K to, BiConsumer<? super K, ? super V> action) {
    Objects.requireNonNull(action);
    Cursor cursor = new Cursor(from, true, to, false);
    while (cursor.hasNext()) {
      cursor.advance();
      action.accept(cursor.key, cursor.value);
    }
  }

  /**
   * Gives each entry with a key in {@code [from, to)} to {@code action}, in key order, while the
   * action returns {@code true}. Unlike the iterator, it makes no entry object.
   *
   * @param from the inclusive lower bound, or {@code null} for no bound
   * @param to the exclusive upper bound, or {@code null} for no bound
   * @param action the action; it returns {@code false} to stop
   */
  public void forEachInRangeWhile(K from, K to, BiPredicate<? super K, ? super V> action) {
    Objects.requireNonNull(action);
    Cursor cursor = new Cursor(from, true, to, false);
    while (cursor.hasNext()) {
      cursor.advance();
      if (!action.test(cursor.key, cursor.value)) {
        return;
      }
    }
  }

  /**
   * Gives an iterator over the entries with a key in a range, in key order. The iterator is
   * weakly consistent. Its {@code remove} method removes the last key from the map.
   *
   * @param from the lower bound, or {@code null} for no bound
   * @param fromInclusive {@code true} if the range includes {@code from}
   * @param to the upper bound, or {@code null} for no bound
   * @param toInclusive {@code true} if the range includes {@code to}
   * @return the iterator
   */
  public Iterator<Map.Entry<K, V>> iterator(
      K from, boolean fromInclusive, K to, boolean toInclusive) {
    return new EntryIterator(new Cursor(from, fromInclusive, to, toInclusive));
  }

  private EntrySet entrySet;
  private KeySet keySet;

  @Override
  public Set<K> keySet() {
    KeySet set = keySet;
    if (set == null) {
      set = new KeySet();
      keySet = set;
    }
    return set;
  }

  /** The keys. Unlike the key set of AbstractMap, it finds and removes a key in O(log n). */
  private final class KeySet extends AbstractSet<K> {

    @Override
    public Iterator<K> iterator() {
      Iterator<Map.Entry<K, V>> entries = BBTreeMap.this.iterator(null, true, null, false);
      return new Iterator<>() {
        @Override
        public boolean hasNext() {
          return entries.hasNext();
        }

        @Override
        public K next() {
          return entries.next().getKey();
        }

        @Override
        public void remove() {
          entries.remove();
        }
      };
    }

    @Override
    public int size() {
      return BBTreeMap.this.size();
    }

    @Override
    public boolean isEmpty() {
      return BBTreeMap.this.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
      return containsKey(o);
    }

    @Override
    public boolean remove(Object o) {
      return BBTreeMap.this.remove(o) != null;
    }

    @Override
    public void clear() {
      BBTreeMap.this.clear();
    }
  }

  @Override
  public Set<Map.Entry<K, V>> entrySet() {
    EntrySet set = entrySet;
    if (set == null) {
      set = new EntrySet();
      entrySet = set;
    }
    return set;
  }

  private final class EntrySet extends AbstractSet<Map.Entry<K, V>> {

    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
      return BBTreeMap.this.iterator(null, true, null, false);
    }

    @Override
    public int size() {
      return BBTreeMap.this.size();
    }

    @Override
    public boolean isEmpty() {
      return BBTreeMap.this.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
      if (!(o instanceof Map.Entry<?, ?> e) || e.getKey() == null) {
        return false;
      }
      V value = get(e.getKey());
      return value != null && value.equals(e.getValue());
    }

    @Override
    public boolean remove(Object o) {
      return o instanceof Map.Entry<?, ?> e
          && e.getKey() != null
          && BBTreeMap.this.remove(e.getKey(), e.getValue());
    }

    @Override
    public void clear() {
      BBTreeMap.this.clear();
    }
  }

  /**
   * An entry whose {@code setValue} writes through to the map with {@code put}. As in {@link
   * java.util.concurrent.ConcurrentHashMap}, it puts the key back if another thread removed it,
   * and it gives the value that the iterator saw.
   */
  private final class WriteThroughEntry extends AbstractMap.SimpleEntry<K, V> {

    private static final long serialVersionUID = 1L;

    WriteThroughEntry(K key, V value) {
      super(key, value);
    }

    @Override
    public V setValue(V value) {
      Objects.requireNonNull(value);
      put(getKey(), value);
      return super.setValue(value);
    }
  }

  private final class EntryIterator implements Iterator<Map.Entry<K, V>> {

    private final Cursor cursor;
    private K last;

    EntryIterator(Cursor cursor) {
      this.cursor = cursor;
    }

    @Override
    public boolean hasNext() {
      return cursor.hasNext();
    }

    @Override
    public Map.Entry<K, V> next() {
      if (!cursor.hasNext()) {
        throw new NoSuchElementException();
      }
      cursor.advance();
      last = cursor.key;
      return new WriteThroughEntry(cursor.key, cursor.value);
    }

    @Override
    public void remove() {
      if (last == null) {
        throw new IllegalStateException();
      }
      BBTreeMap.this.remove(last);
      last = null;
    }
  }

  /**
   * Walks the leaves in key order. The cursor copies the entries of one leaf at a time into a
   * buffer, with an optimistic read. If the read is not valid, it finds the leaf again from the
   * root, after the last key that it copied.
   */
  private final class Cursor {

    private final K from;
    private final boolean fromInclusive;
    private final K to;
    private final boolean toInclusive;
    private final Object[] bufferKeys = new Object[leafCapacity];
    private final Object[] bufferValues = new Object[leafCapacity];
    private int bufferCount;
    private int bufferPos;

    /** The leaf to copy next, or {@code null} at the end of the leaves. */
    private Leaf nextLeaf;

    /** The last key that the cursor copied, or {@code null} before the first copy. */
    private K lastCopied;

    private boolean done;

    /** The entry that {@link #advance()} moved to. */
    K key;

    V value;

    Cursor(K from, boolean fromInclusive, K to, boolean toInclusive) {
      this.from = from;
      this.fromInclusive = fromInclusive;
      this.to = to;
      this.toInclusive = toInclusive;
      seek(from, fromInclusive);
    }

    boolean hasNext() {
      while (bufferPos == bufferCount) {
        if (done || nextLeaf == null) {
          return false;
        }
        copyNext();
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    void advance() {
      key = (K) bufferKeys[bufferPos];
      value = (V) bufferValues[bufferPos];
      bufferPos++;
    }

    /** Fills the buffer from the leaf that holds {@code from}, from {@code from} on. */
    private void seek(K from, boolean inclusive) {
      restart:
      while (true) {
        Node node = root;
        long v = node.version();
        if (Node.isLocked(v) || node != root) {
          Thread.onSpinWait();
          continue;
        }
        try {
          while (node instanceof Inner inner) {
            Node child = inner.child(from == null ? 0 : childIndex(inner, from));
            long cv = child.version();
            if (Node.isLocked(cv) || !inner.validate(v)) {
              Thread.onSpinWait();
              continue restart;
            }
            node = child;
            v = cv;
          }
          Leaf leaf = (Leaf) node;
          int start = 0;
          if (from != null) {
            int pos = find(leaf, from);
            start = pos >= 0 ? (inclusive ? pos : pos + 1) : -pos - 1;
          }
          if (copy(leaf, v, start)) {
            return;
          }
        } catch (RuntimeException e) {
          if (node.validate(v)) {
            throw e;
          }
        }
      }
    }

    private void copyNext() {
      Leaf leaf = nextLeaf;
      long v = leaf.version();
      if (Node.isLocked(v) || !copy(leaf, v, 0)) {
        reseek();
      }
    }

    private void reseek() {
      if (lastCopied == null) {
        seek(from, fromInclusive);
      } else {
        seek(lastCopied, false);
      }
    }

    /**
     * Copies the entries of a leaf from index {@code start}, up to the upper bound. Gives {@code
     * false} and leaves the cursor as it was if the read is not valid.
     */
    @SuppressWarnings("unchecked")
    private boolean copy(Leaf leaf, long v, int start) {
      int n = Math.min(leaf.count(), leaf.keys.length);
      int m = 0;
      boolean end = false;
      try {
        for (int i = start; i < n; i++) {
          Object k = leaf.keys[i];
          if (to != null) {
            int c = comparator.compare((K) k, to);
            if (c > 0 || (c == 0 && !toInclusive)) {
              end = true;
              break;
            }
          }
          bufferKeys[m] = k;
          bufferValues[m] = valueAt(leaf, i);
          m++;
        }
      } catch (RuntimeException e) {
        if (leaf.validate(v)) {
          throw e;
        }
        return false;
      }
      Leaf next = leaf.next;
      if (!leaf.validate(v)) {
        return false;
      }
      bufferCount = m;
      bufferPos = 0;
      nextLeaf = next;
      done = end;
      if (m > 0) {
        lastCopied = (K) bufferKeys[m - 1];
      }
      return true;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Scans of primitive keys

  /** Receives the entries of {@link #scanLong}. */
  interface LongEntryVisitor {

    /**
     * Receives one entry. In a map of primitive values, {@code value} is {@code null} and the
     * value is {@code longValue}. Otherwise {@code longValue} is 0.
     *
     * @return {@code false} to stop the scan
     */
    boolean visit(long key, Object value, long longValue);
  }

  /**
   * Gives the entries with a key from {@code from} to {@code to} to {@code visitor}, in key order,
   * in a map of {@link InlineKey#LONG}. The scan reads no key object: for this key type, the window
   * of a key holds every byte after the prefix, and the lower fence of the leaf holds the prefix.
   *
   * <p>As the {@link Cursor} does, the scan copies one leaf at a time with an optimistic read, and
   * gives the entries to the visitor only after the read is valid. It is weakly consistent.
   */
  void scanLong(long from, long to, boolean toInclusive, LongEntryVisitor visitor) {
    int capacity = leafCapacity;
    long[] keys = new long[capacity];
    Object[] values = primitiveValues ? null : new Object[capacity];
    long[] longValues = primitiveValues ? new long[capacity] : null;
    // The smallest key that the scan did not give yet.
    long next = from;
    Leaf leaf = null;
    while (true) {
      long v;
      int start;
      if (leaf == null) {
        Node node = root;
        v = node.version();
        if (Node.isLocked(v) || node != root) {
          Thread.onSpinWait();
          continue;
        }
        try {
          boolean stale = false;
          while (node instanceof Inner inner) {
            Node child = inner.child(childIndexLong(inner, next));
            long cv = child.version();
            if (Node.isLocked(cv) || !inner.validate(v)) {
              stale = true;
              break;
            }
            node = child;
            v = cv;
          }
          if (stale) {
            Thread.onSpinWait();
            continue;
          }
          leaf = (Leaf) node;
          int pos = findLong(leaf.head, next);
          start = pos >= 0 ? pos : -pos - 1;
        } catch (RuntimeException e) {
          if (node.validate(v)) {
            throw e;
          }
          leaf = null;
          continue;
        }
      } else {
        v = leaf.version();
        if (Node.isLocked(v)) {
          leaf = null;
          continue;
        }
        start = 0;
      }
      int m = 0;
      boolean end = false;
      Leaf following;
      try {
        int n = Math.min(leaf.count(), capacity);
        int prefix = leaf.prefix();
        int shift = prefix << 3;
        long top =
            prefix == 0
                ? 0L
                : ((Long) leaf.low ^ Long.MIN_VALUE) & (-1L << (Long.SIZE - shift));
        long[] w = leaf.head;
        for (int i = start; i < n; i++) {
          long key = (top | ((w[Node.W + i] ^ Long.MIN_VALUE) >>> shift)) ^ Long.MIN_VALUE;
          if (key < next) {
            continue;
          }
          if (key > to || (key == to && !toInclusive)) {
            end = true;
            break;
          }
          keys[m] = key;
          if (longValues != null) {
            longValues[m] = leaf.longValues[i];
          } else {
            values[m] = leaf.values[i];
          }
          m++;
        }
        following = leaf.next;
      } catch (RuntimeException e) {
        if (leaf.validate(v)) {
          throw e;
        }
        leaf = null;
        continue;
      }
      if (!leaf.validate(v)) {
        leaf = null;
        continue;
      }
      for (int i = 0; i < m; i++) {
        boolean more =
            longValues != null
                ? visitor.visit(keys[i], null, longValues[i])
                : visitor.visit(keys[i], values[i], 0L);
        if (!more) {
          return;
        }
      }
      if (m > 0) {
        if (keys[m - 1] == Long.MAX_VALUE) {
          return;
        }
        next = keys[m - 1] + 1;
      }
      if (end || following == null) {
        return;
      }
      leaf = following;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Test support

  /** Gives the number of levels of the tree. */
  int height() {
    int h = 1;
    for (Node node = root; node instanceof Inner inner; node = inner.child(0)) {
      h++;
    }
    return h;
  }

  /** Gives the root, for analysis tools in this package. */
  Node rootNode() {
    return root;
  }

  /** Gives the number of leaves in the leaf chain. */
  int leafCount() {
    return countLeaves(false);
  }

  /** Gives the number of empty leaves in the leaf chain. */
  int emptyLeafCount() {
    return countLeaves(true);
  }

  private int countLeaves(boolean emptyOnly) {
    Node node = root;
    while (node instanceof Inner inner) {
      node = inner.child(0);
    }
    int count = 0;
    for (Leaf leaf = (Leaf) node; leaf != null; leaf = leaf.next) {
      if (!emptyOnly || leaf.count() == 0) {
        count++;
      }
    }
    return count;
  }

  /**
   * Checks the structure of the tree and gives the number of entries. Call it only when no write
   * runs.
   *
   * @throws AssertionError if the tree is not valid
   */
  int checkInvariants() {
    Node top = root;
    check(top.low == null && top.high == null, "the root has fences");
    Leaf[] previous = new Leaf[1];
    int[] leafDepth = {-1};
    int entries = checkNode(top, 0, leafDepth, previous);
    check(previous[0] == null || previous[0].next == null, "the last leaf has a next leaf");
    check(entries == size(), "size " + size() + " but " + entries + " entries");
    return entries;
  }

  @SuppressWarnings("unchecked")
  private int checkNode(Node node, int depth, int[] leafDepth, Leaf[] previous) {
    check(!Node.isLocked(node.version()), "a node is locked");
    int n = node.count();
    check(n >= 0 && n <= node.capacity(), "count out of range: " + n);
    check(Node.isLeaf(node.head) == node instanceof Leaf, "wrong leaf flag in the head");
    check(node.head.length == Node.W + (inline != null ? node.capacity() : 0), "wrong head length");
    for (int i = 0; i < n; i++) {
      K k = (K) node.keys[i];
      check(k != null, "null key at " + i);
      if (i > 0) {
        check(comparator.compare((K) node.keys[i - 1], k) < 0, "keys out of order at " + i);
      }
      check(node.low == null || comparator.compare((K) node.low, k) <= 0, "key below fence");
      check(node.high == null || comparator.compare(k, (K) node.high) < 0, "key above fence");
    }
    for (int i = n; i < node.capacity(); i++) {
      check(node.keys[i] == null, "stale key at " + i);
    }
    if (node instanceof Inner inner) {
      // A separator equal to the lower fence would give child 0 an empty range.
      check(n == 0 || node.low == null || comparator.compare((K) node.low, (K) node.keys[0]) < 0,
          "a separator equals the lower fence");
      Object[] slots = inner.slots;
      for (int i = 0; i < inner.childSlots(); i++) {
        Node child = inner.child(i);
        if (i > n) {
          check(child == null && Inner.childHead(slots, i) == null, "stale child at " + i);
          check(Inner.childBody(slots, i) == null, "stale child body at " + i);
        } else if (child != null) {
          check(Inner.childHead(slots, i) == child.head, "stale child head at " + i);
          check(Inner.childBody(slots, i) == child.body(), "stale child body at " + i);
        }
      }
    } else {
      Leaf leaf = (Leaf) node;
      check((leaf.longValues != null) == primitiveValues, "wrong value array");
      for (int i = n; leaf.values != null && i < leaf.values.length; i++) {
        check(leaf.values[i] == null, "stale value at " + i);
      }
    }
    if (inline != null) {
      int prefix =
          node.low == null || node.high == null
              ? 0
              : inline.commonPrefix((K) node.low, (K) node.high);
      check(node.prefix() == prefix, "prefix " + node.prefix() + " but expected " + prefix);
      int[] nw = node instanceof Inner inner ? inner.narrow : null;
      check((nw != null) == (narrow && node instanceof Inner), "wrong window width");
      for (int i = 0; i < n; i++) {
        long w = window((K) node.keys[i], prefix);
        check(node.head[Node.W + i] == w, "stale window at " + i);
        check(nw == null || nw[i] == narrow(w), "stale 4-byte window at " + i);
      }
      if (nw != null) {
        check(nw.length % NARROW_ALIGNMENT == 0, "narrow windows not aligned");
        for (int i = n; i < nw.length; i++) {
          check(nw[i] == Integer.MAX_VALUE, "tail window not the maximum at " + i);
        }
      }
    }
    if (node instanceof Leaf leaf) {
      if (leafDepth[0] < 0) {
        leafDepth[0] = depth;
      }
      check(leafDepth[0] == depth, "leaves at different depths");
      if (previous[0] != null) {
        check(previous[0].next == leaf, "leaf chain out of order");
        check(Objects.equals(previous[0].high, leaf.low), "leaf fences do not meet");
      } else {
        check(leaf.low == null, "the first leaf has a lower fence");
      }
      previous[0] = leaf;
      for (int i = 0; leaf.values != null && i < n; i++) {
        check(leaf.values[i] != null, "null value at " + i);
      }
      return n;
    }
    Inner inner = (Inner) node;
    int entries = 0;
    for (int i = 0; i <= n; i++) {
      Node child = inner.child(i);
      check(child != null, "null child at " + i);
      Object low = i == 0 ? inner.low : inner.keys[i - 1];
      Object high = i == n ? inner.high : inner.keys[i];
      check(Objects.equals(child.low, low), "child " + i + " has the wrong lower fence");
      check(Objects.equals(child.high, high), "child " + i + " has the wrong upper fence");
      entries += checkNode(child, depth + 1, leafDepth, previous);
    }
    return entries;
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
