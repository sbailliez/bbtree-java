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

/**
 * A concurrent sorted map: a B+ tree with inline keys and optimistic lock coupling, after
 * <a href="https://github.com/XiangpengHao/bbtree">bbtree</a> by Xiangpeng Hao.
 *
 * <p><b>Search.</b> If you give the map an {@link InlineKey}, each node keeps a {@code long}
 * window of each key, taken right after the encoding prefix that all keys of the node share. The
 * node prefix comes from the two separators around the node in its parent, so no node stores it.
 * A node search counts the windows that are smaller than the window of the target, with no
 * branch in the loop. The map reads a key object only when two windows tie. For keys of a fixed
 * length that the window covers, such as {@link Long}, a tie is a match and the map reads no key
 * object at all.
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
 * <p><b>Removal.</b> A removal that empties a leaf unlinks the leaf when the leaf has a left
 * sibling in the same parent. The map does not merge leaves that are not empty, and the tree
 * height does not decrease.
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

  /** The number of lookups that {@link #getMany} interleaves. */
  private static final int BATCH_LANES = 16;

  private final Comparator<? super K> comparator;
  private final InlineKey<K> inline;

  /** A node with a prefix of this length or more compares no key on a window tie. */
  private final int exactFrom;

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

  @SuppressWarnings("unchecked")
  BBTreeMap(
      Comparator<? super K> comparator, InlineKey<K> inline, int leafCapacity, int innerCapacity) {
    if (leafCapacity < MIN_CAPACITY || leafCapacity > MAX_CAPACITY) {
      throw new IllegalArgumentException("leafCapacity out of range: " + leafCapacity);
    }
    if (innerCapacity < MIN_CAPACITY || innerCapacity > MAX_CAPACITY) {
      throw new IllegalArgumentException("innerCapacity out of range: " + innerCapacity);
    }
    this.comparator =
        comparator != null ? comparator : (Comparator<? super K>) Comparator.naturalOrder();
    this.inline = inline;
    int fixed = inline == null ? -1 : inline.fixedLength();
    this.exactFrom = fixed < 0 ? Integer.MAX_VALUE : fixed - Long.BYTES;
    this.leafCapacity = leafCapacity;
    this.innerCapacity = innerCapacity;
    this.root = new Leaf(leafCapacity, inline != null);
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

  /**
   * Counts the windows that are smaller than {@code target} and the windows that are not larger.
   * The loop has no branch, so the JIT can use conditional moves or SIMD.
   *
   * @return the first count in the high 32 bits and the second count in the low 32 bits
   */
  private static long countWindows(long[] windows, int n, long target) {
    int lt = 0;
    int le = 0;
    for (int i = 0; i < n; i++) {
      long w = windows[i];
      lt += w < target ? 1 : 0;
      le += w <= target ? 1 : 0;
    }
    return ((long) lt << 32) | le;
  }

  /**
   * Finds {@code key} in a leaf.
   *
   * @return the index of the key, or {@code -(insertion point) - 1} if the leaf does not have it
   */
  @SuppressWarnings("unchecked")
  private int find(Node node, K key) {
    Object[] keys = node.keys;
    int n = Math.min(node.count, keys.length);
    int lo = 0;
    int hi = n;
    if (inline != null) {
      long counts = countWindows(node.windows, n, window(key, node.prefix));
      lo = (int) (counts >>> 32);
      hi = (int) counts;
      if (lo == hi) {
        return -lo - 1;
      }
      if (node.prefix >= exactFrom) {
        return lo;
      }
    }
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
  @SuppressWarnings("unchecked")
  private int childIndex(Inner node, K key) {
    Object[] keys = node.keys;
    int n = Math.min(node.count, keys.length);
    int lo = 0;
    int hi = n;
    if (inline != null) {
      long counts = countWindows(node.windows, n, window(key, node.prefix));
      lo = (int) (counts >>> 32);
      hi = (int) counts;
      if (lo == hi || node.prefix >= exactFrom) {
        return hi;
      }
    }
    // The number of separators that are not larger than the key.
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

  // ---------------------------------------------------------------------------------------------
  // Reads

  @Override
  public V get(Object key) {
    K k = key(key);
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
          Node child = inner.children[childIndex(inner, k)];
          long cv = child.version();
          if (Node.isLocked(cv) || !inner.validate(v)) {
            Thread.onSpinWait();
            continue restart;
          }
          node = child;
          v = cv;
        }
        Leaf leaf = (Leaf) node;
        int pos = find(leaf, k);
        Object value = pos >= 0 ? leaf.values[pos] : null;
        if (leaf.validate(v)) {
          @SuppressWarnings("unchecked")
          V result = (V) value;
          return result;
        }
      } catch (RuntimeException e) {
        if (node.validate(v)) {
          throw e;
        }
      }
    }
  }

  @Override
  public boolean containsKey(Object key) {
    return get(key) != null;
  }

  /**
   * Looks up many keys at the same time. The lookups go down the tree one level at a time,
   * together, so that the CPU waits for the cache misses of different lookups in parallel.
   *
   * @param keys the keys to look up, none {@code null}
   * @param results the array that receives the value of {@code keys[i]} at index {@code i}, or
   *     {@code null} if the map does not have the key; at least as long as {@code keys}
   */
  @SuppressWarnings("unchecked")
  public void getMany(K[] keys, V[] results) {
    if (results.length < keys.length) {
      throw new IllegalArgumentException("results is shorter than keys");
    }
    int lanes = Math.min(BATCH_LANES, keys.length);
    Node[] nodes = new Node[lanes];
    long[] versions = new long[lanes];
    for (int start = 0; start < keys.length; start += lanes) {
      int n = Math.min(lanes, keys.length - start);
      for (int j = 0; j < n; j++) {
        Objects.requireNonNull(keys[start + j]);
      }
      Node top = root;
      long tv = top.version();
      if (Node.isLocked(tv) || top != root) {
        for (int j = 0; j < n; j++) {
          results[start + j] = get(keys[start + j]);
        }
        continue;
      }
      Arrays.fill(nodes, 0, n, top);
      Arrays.fill(versions, 0, n, tv);
      // All leaves are at the same depth, so the lanes reach the leaves together.
      boolean inner = top instanceof Inner;
      while (inner) {
        for (int j = 0; j < n; j++) {
          nodes[j] = descendOne(nodes[j], versions, j, keys[start + j]);
        }
        inner = false;
        for (int j = 0; j < n; j++) {
          if (nodes[j] instanceof Inner) {
            inner = true;
            break;
          }
        }
      }
      for (int j = 0; j < n; j++) {
        K k = keys[start + j];
        Object value = null;
        boolean valid = false;
        if (nodes[j] instanceof Leaf leaf) {
          try {
            int pos = find(leaf, k);
            value = pos >= 0 ? leaf.values[pos] : null;
            valid = leaf.validate(versions[j]);
          } catch (RuntimeException e) {
            if (leaf.validate(versions[j])) {
              throw e;
            }
          }
        }
        results[start + j] = valid ? (V) value : get(k);
      }
    }
  }

  /**
   * Moves one lane of {@link #getMany} down one level. Gives {@code null} if the read was not
   * valid. The lane then falls back to {@link #get}.
   */
  private Node descendOne(Node node, long[] versions, int lane, K key) {
    if (!(node instanceof Inner inner)) {
      return null;
    }
    long v = versions[lane];
    try {
      Node child = inner.children[childIndex(inner, key)];
      long cv = child.version();
      if (Node.isLocked(cv) || !inner.validate(v)) {
        return null;
      }
      versions[lane] = cv;
      return child;
    } catch (RuntimeException e) {
      if (inner.validate(v)) {
        throw e;
      }
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Writes

  /**
   * Locks and gives the leaf that holds {@code key}. If {@code forInsert} is {@code true}, the
   * leaf has room for one more entry or already has the key. The caller must unlock the leaf.
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
          if (forInsert && inner.count >= inner.capacity()) {
            // Split a full inner node on the way down, so that a split below always has room
            // in its parent.
            trySplit(parent, pv, slot, inner, v, key);
            continue restart;
          }
          int i = childIndex(inner, key);
          Node child = inner.children[i];
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
        Leaf leaf = (Leaf) node;
        if (forInsert && leaf.count >= leaf.capacity()) {
          boolean present = find(leaf, key) >= 0;
          if (!leaf.validate(v)) {
            continue;
          }
          if (!present) {
            trySplit(parent, pv, slot, leaf, v, key);
            continue;
          }
        }
        if (leaf.tryUpgrade(v)) {
          return leaf;
        }
        Thread.onSpinWait();
      } catch (RuntimeException e) {
        if (node.validate(v)) {
          throw e;
        }
      }
    }
  }

  /**
   * Splits {@code node} for the insert of {@code key}, if neither the node nor its parent changed
   * since the versions that the caller read. Does nothing if one of them changed: the caller
   * starts again from the root.
   */
  private void trySplit(Inner parent, long pv, int slot, Node node, long v, K key) {
    if (parent == null) {
      if (!node.tryUpgrade(v)) {
        return;
      }
      if (root != node) {
        node.unlock();
        return;
      }
      Node right = split(node, key);
      Inner top = new Inner(innerCapacity, inline != null);
      top.children[0] = node;
      insertChild(top, 0, right.low, right);
      root = top;
      node.unlock();
      return;
    }
    if (!parent.tryUpgrade(pv)) {
      return;
    }
    if (!node.tryUpgrade(v)) {
      parent.unlock();
      return;
    }
    Node right = split(node, key);
    insertChild(parent, slot, right.low, right);
    node.unlock();
    parent.unlock();
  }

  /**
   * Moves the upper half of a locked node into a new node and gives it. If {@code key} goes after
   * the last key of the last node of its level, the keys arrive in ascending order, as
   * time-ordered IDs do. Then the split moves only the last key, and leaves the left node full.
   */
  private Node split(Node node, K key) {
    @SuppressWarnings("unchecked")
    boolean append =
        node.high == null && comparator.compare(key, (K) node.keys[node.count - 1]) > 0;
    if (node instanceof Leaf leaf) {
      return splitLeaf(leaf, append ? leaf.count - 1 : leaf.count >>> 1);
    }
    return splitInner((Inner) node, append ? node.count - 2 : node.count >>> 1);
  }

  /** Moves the entries from index {@code mid} on into a new right leaf. */
  private Leaf splitLeaf(Leaf leaf, int mid) {
    Leaf right = new Leaf(leafCapacity, inline != null);
    int n = leaf.count;
    int m = n - mid;
    System.arraycopy(leaf.keys, mid, right.keys, 0, m);
    System.arraycopy(leaf.values, mid, right.values, 0, m);
    Arrays.fill(leaf.keys, mid, n, null);
    Arrays.fill(leaf.values, mid, n, null);
    right.count = m;
    leaf.count = mid;
    right.low = right.keys[0];
    right.high = leaf.high;
    leaf.high = right.low;
    right.next = leaf.next;
    refit(leaf);
    refit(right);
    leaf.next = right;
    return right;
  }

  /** Moves separator {@code mid} up, and the separators after it into a new right node. */
  private Inner splitInner(Inner inner, int mid) {
    Inner right = new Inner(innerCapacity, inline != null);
    int n = inner.count;
    int m = n - mid - 1;
    Object separator = inner.keys[mid];
    System.arraycopy(inner.keys, mid + 1, right.keys, 0, m);
    System.arraycopy(inner.children, mid + 1, right.children, 0, m + 1);
    Arrays.fill(inner.keys, mid, n, null);
    Arrays.fill(inner.children, mid + 1, n + 1, null);
    right.count = m;
    inner.count = mid;
    right.low = separator;
    right.high = inner.high;
    inner.high = separator;
    refit(inner);
    refit(right);
    return right;
  }

  /**
   * Computes the prefix of a node from its fences, and the windows of its keys from the prefix.
   * A node that is open on one side has no prefix.
   */
  @SuppressWarnings("unchecked")
  private void refit(Node node) {
    if (inline == null) {
      return;
    }
    int prefix =
        node.low == null || node.high == null
            ? 0
            : inline.commonPrefix((K) node.low, (K) node.high);
    node.prefix = prefix;
    for (int i = 0; i < node.count; i++) {
      node.windows[i] = window((K) node.keys[i], prefix);
    }
  }

  /** Puts {@code separator} at index {@code slot} of a locked inner node, with {@code right}. */
  @SuppressWarnings("unchecked")
  private void insertChild(Inner node, int slot, Object separator, Node right) {
    int n = node.count;
    long w = inline != null ? window((K) separator, node.prefix) : 0L;
    System.arraycopy(node.keys, slot, node.keys, slot + 1, n - slot);
    System.arraycopy(node.children, slot + 1, node.children, slot + 2, n - slot);
    node.keys[slot] = separator;
    node.children[slot + 1] = right;
    if (inline != null) {
      System.arraycopy(node.windows, slot, node.windows, slot + 1, n - slot);
      node.windows[slot] = w;
    }
    node.count = n + 1;
  }

  /** Puts an entry at index {@code pos} of a locked leaf that has room. */
  private void insertAt(Leaf leaf, int pos, K key, V value) {
    int n = leaf.count;
    long w = inline != null ? window(key, leaf.prefix) : 0L;
    System.arraycopy(leaf.keys, pos, leaf.keys, pos + 1, n - pos);
    System.arraycopy(leaf.values, pos, leaf.values, pos + 1, n - pos);
    leaf.keys[pos] = key;
    leaf.values[pos] = value;
    if (inline != null) {
      System.arraycopy(leaf.windows, pos, leaf.windows, pos + 1, n - pos);
      leaf.windows[pos] = w;
    }
    leaf.count = n + 1;
  }

  /** Removes the entry at index {@code pos} of a locked leaf. */
  private static void removeAt(Leaf leaf, int pos) {
    int n = leaf.count;
    int tail = n - pos - 1;
    System.arraycopy(leaf.keys, pos + 1, leaf.keys, pos, tail);
    System.arraycopy(leaf.values, pos + 1, leaf.values, pos, tail);
    if (leaf.windows != null) {
      System.arraycopy(leaf.windows, pos + 1, leaf.windows, pos, tail);
    }
    leaf.keys[n - 1] = null;
    leaf.values[n - 1] = null;
    leaf.count = n - 1;
  }

  private V put(K key, V value, boolean onlyIfAbsent) {
    Objects.requireNonNull(value);
    Leaf leaf = lockLeaf(key, true);
    try {
      int pos = find(leaf, key);
      if (pos >= 0) {
        @SuppressWarnings("unchecked")
        V old = (V) leaf.values[pos];
        if (!onlyIfAbsent) {
          leaf.values[pos] = value;
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
    V old;
    boolean empty;
    try {
      int pos = find(leaf, key);
      if (pos < 0) {
        return null;
      }
      @SuppressWarnings("unchecked")
      V value = (V) leaf.values[pos];
      if (expected != null && !expected.equals(value)) {
        return null;
      }
      old = value;
      removeAt(leaf, pos);
      empty = leaf.count == 0;
    } finally {
      leaf.unlock();
    }
    size.decrement();
    if (empty) {
      unlinkEmptyLeaf(key);
    }
    return old;
  }

  @Override
  public V remove(Object key) {
    return doRemove(key(key), null);
  }

  @Override
  public boolean remove(Object key, Object value) {
    return value != null && doRemove(key(key), value) != null;
  }

  @Override
  public V replace(K key, V value) {
    Objects.requireNonNull(value);
    Leaf leaf = lockLeaf(key(key), false);
    try {
      int pos = find(leaf, key);
      if (pos < 0) {
        return null;
      }
      @SuppressWarnings("unchecked")
      V old = (V) leaf.values[pos];
      leaf.values[pos] = value;
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
    try {
      int pos = find(leaf, key);
      if (pos < 0 || !oldValue.equals(leaf.values[pos])) {
        return false;
      }
      leaf.values[pos] = newValue;
      return true;
    } finally {
      leaf.unlock();
    }
  }

  /**
   * Unlinks the leaf of {@code key} if it is empty and has a left sibling in the same parent. The
   * left sibling takes over the key range of the empty leaf. The unlinked leaf stays locked for
   * ever, so that every reader that still holds it starts again. This is best effort: if a node
   * changed, the leaf stays in the tree.
   */
  private void unlinkEmptyLeaf(K key) {
    Node node = root;
    long v = node.version();
    if (Node.isLocked(v) || node != root) {
      return;
    }
    Inner parent = null;
    long pv = 0;
    int slot = 0;
    try {
      while (node instanceof Inner inner) {
        int i = childIndex(inner, key);
        Node child = inner.children[i];
        long cv = child.version();
        if (Node.isLocked(cv) || !inner.validate(v)) {
          return;
        }
        parent = inner;
        pv = v;
        slot = i;
        node = child;
        v = cv;
      }
      if (parent == null || slot == 0 || node.count != 0) {
        return;
      }
      Leaf prev = (Leaf) parent.children[slot - 1];
      long prevVersion = prev.version();
      if (!parent.tryUpgrade(pv)) {
        return;
      }
      if (!prev.tryUpgrade(prevVersion)) {
        parent.unlock();
        return;
      }
      if (!node.tryUpgrade(v)) {
        prev.unlock();
        parent.unlock();
        return;
      }
      Leaf leaf = (Leaf) node;
      prev.high = leaf.high;
      prev.next = leaf.next;
      refit(prev);
      removeChild(parent, slot);
      prev.unlock();
      parent.unlock();
      // The leaf stays locked: it is no longer in the tree.
    } catch (RuntimeException e) {
      if (node.validate(v)) {
        throw e;
      }
    }
  }

  /** Removes child {@code slot} (at least 1) and the separator on its left from a locked node. */
  private static void removeChild(Inner node, int slot) {
    int n = node.count;
    int tail = n - slot;
    System.arraycopy(node.keys, slot, node.keys, slot - 1, tail);
    System.arraycopy(node.children, slot + 1, node.children, slot, tail);
    if (node.windows != null) {
      System.arraycopy(node.windows, slot, node.windows, slot - 1, tail);
    }
    node.keys[n - 1] = null;
    node.children[n] = null;
    node.count = n - 1;
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

  /** An entry whose {@code setValue} writes through to the map. */
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
            Node child = inner.children[from == null ? 0 : childIndex(inner, from)];
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
      int n = Math.min(leaf.count, leaf.keys.length);
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
          bufferValues[m] = leaf.values[i];
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
  // Test support

  /** Gives the number of levels of the tree. */
  int height() {
    int h = 1;
    for (Node node = root; node instanceof Inner inner; node = inner.children[0]) {
      h++;
    }
    return h;
  }

  /** Gives the number of leaves in the leaf chain. */
  int leafCount() {
    Node node = root;
    while (node instanceof Inner inner) {
      node = inner.children[0];
    }
    int count = 0;
    for (Leaf leaf = (Leaf) node; leaf != null; leaf = leaf.next) {
      count++;
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
    int n = node.count;
    check(n >= 0 && n <= node.capacity(), "count out of range: " + n);
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
    if (inline != null) {
      int prefix =
          node.low == null || node.high == null
              ? 0
              : inline.commonPrefix((K) node.low, (K) node.high);
      check(node.prefix == prefix, "prefix " + node.prefix + " but expected " + prefix);
      for (int i = 0; i < n; i++) {
        check(node.windows[i] == window((K) node.keys[i], prefix), "stale window at " + i);
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
      for (int i = 0; i < n; i++) {
        check(leaf.values[i] != null, "null value at " + i);
      }
      return n;
    }
    Inner inner = (Inner) node;
    int entries = 0;
    for (int i = 0; i <= n; i++) {
      Node child = inner.children[i];
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
