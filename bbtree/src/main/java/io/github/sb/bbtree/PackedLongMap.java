package io.github.sb.bbtree;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.LongAdder;

/**
 * A concurrent sorted map of primitive {@code long} keys to primitive {@code long} values, with
 * the keys in signed order. It has no key or value objects: each node is a {@code long[]}, as the
 * nodes of the Rust bbtree are pages.
 *
 * <ul>
 *   <li>A leaf is one {@code long[]}: the version, the count, the keys, then the values. A lookup
 *       compares the keys themselves, and finds the value in the same array.
 *   <li>An inner node is a {@code long[]} with the version, the count and the separators, and an
 *       {@code Object[]} with the arrays of each child in pairs: the child, and for an inner
 *       child its own pairs.
 * </ul>
 *
 * <p>The concurrency is the one of {@link BBTreeMap}: each node has a version lock, a read
 * validates the version of each node after it reads the node, and a write locks only the leaf,
 * plus the parent when a node splits. Full inner nodes split on the way down. A scan finds each
 * next leaf from the root, with the upper bound that the separators give on the way down, so the
 * leaves need no links.
 *
 * <p>A map cannot hold {@code null}, so the methods that may find no entry take the value to give
 * back in that case: {@code absent}. A removal leaves an empty leaf in the tree; the map does not
 * merge nodes.
 */
public final class PackedLongMap {

  /** Receives the entries of a scan. */
  @FunctionalInterface
  public interface EntryPredicate {

    /**
     * Receives one entry.
     *
     * @param key the key
     * @param value the value
     * @return {@code false} to stop the scan
     */
    boolean test(long key, long value);
  }

  private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(long[].class);

  /** The slot of the version in a node. */
  private static final int VERSION = 0;

  /** The slot of the count (bits 0 to 31) and the leaf flag. */
  private static final int META = 1;

  /** The slot of the first key or separator. */
  private static final int K = 2;

  private static final long LEAF = 1L << 32;
  private static final long COUNT = 0xFFFF_FFFFL;

  /** The number of lookups that {@link #getMany} interleaves. */
  private static final int BATCH_LANES = 16;

  /** The root: its node, and its pairs if it is an inner node. */
  private record Root(long[] node, Object[] children) {}

  private final int leafCapacity;
  private final int innerCapacity;

  /** The slot of value 0 in a leaf. */
  private final int values;

  private final LongAdder size = new LongAdder();

  /** The root. It changes only while a writer holds the old root. */
  private volatile Root root;

  /** Makes an empty map with the default node capacities: 32 entries in a leaf, 64 in an inner node. */
  public PackedLongMap() {
    this(BBTreeMap.DEFAULT_LEAF_CAPACITY, BBTreeMap.DEFAULT_INNER_CAPACITY);
  }

  /**
   * Makes an empty map with the node capacities that you give.
   *
   * @param leafCapacity the number of entries in a leaf, from 4 to 1024
   * @param innerCapacity the number of separators in an inner node, from 4 to 1024
   */
  public PackedLongMap(int leafCapacity, int innerCapacity) {
    if (leafCapacity < BBTreeMap.MIN_CAPACITY || leafCapacity > BBTreeMap.MAX_CAPACITY) {
      throw new IllegalArgumentException("leafCapacity out of range: " + leafCapacity);
    }
    if (innerCapacity < BBTreeMap.MIN_CAPACITY || innerCapacity > BBTreeMap.MAX_CAPACITY) {
      throw new IllegalArgumentException("innerCapacity out of range: " + innerCapacity);
    }
    this.leafCapacity = leafCapacity;
    this.innerCapacity = innerCapacity;
    this.values = K + leafCapacity;
    this.root = new Root(newLeaf(), null);
  }

  // ---------------------------------------------------------------------------------------------
  // Nodes

  private long[] newLeaf() {
    long[] leaf = new long[K + 2 * leafCapacity];
    leaf[META] = LEAF;
    return leaf;
  }

  private long[] newInner() {
    return new long[K + innerCapacity];
  }

  private Object[] newChildren() {
    return new Object[2 * (innerCapacity + 1)];
  }

  private static boolean isLeaf(long[] node) {
    return (node[META] & LEAF) != 0;
  }

  private static int count(long[] node) {
    return (int) (node[META] & COUNT);
  }

  private static void setCount(long[] node, int count) {
    node[META] = (node[META] & LEAF) | count;
  }

  private static long version(long[] node) {
    return (long) SLOT.getAcquire(node, VERSION);
  }

  private static boolean isLocked(long version) {
    return (version & 1L) != 0;
  }

  /** Gives true if no writer changed the node since the reader read {@code version}. */
  private static boolean validate(long[] node, long version) {
    VarHandle.acquireFence();
    return (long) SLOT.getAcquire(node, VERSION) == version;
  }

  /** Locks the node if its version is still {@code version}. */
  private static boolean tryLock(long[] node, long version) {
    if (isLocked(version) || !SLOT.compareAndSet(node, VERSION, version, version + 1)) {
      return false;
    }
    VarHandle.storeStoreFence();
    return true;
  }

  /** Unlocks the node. The release store publishes the plain writes before it. */
  private static void unlock(long[] node) {
    SLOT.setRelease(node, VERSION, (long) SLOT.get(node, VERSION) + 1);
  }

  /** Unlocks a node that the writer did not change, at its version from before the lock. */
  private static void unlockUnchanged(long[] node) {
    SLOT.setRelease(node, VERSION, (long) SLOT.get(node, VERSION) - 1);
  }

  /** Gives the number of separators of an inner node that are not larger than {@code key}. */
  private int childIndex(long[] node, long key) {
    int n = Math.min(count(node), innerCapacity);
    int i = 0;
    while (i < n && node[K + i] <= key) {
      i++;
    }
    return i;
  }

  /** Gives the number of keys of a leaf that are smaller than {@code key}. */
  private int below(long[] leaf, long key) {
    int n = Math.min(count(leaf), leafCapacity);
    int i = 0;
    while (i < n && leaf[K + i] < key) {
      i++;
    }
    return i;
  }

  /** Gives the index of {@code key} in a leaf, or {@code -(insertion point) - 1}. */
  private int find(long[] leaf, long key) {
    int i = below(leaf, key);
    return i < Math.min(count(leaf), leafCapacity) && leaf[K + i] == key ? i : -i - 1;
  }

  // ---------------------------------------------------------------------------------------------
  // Reads

  /**
   * Gives the value of {@code key}.
   *
   * @param key the key
   * @param absent the value to give if the map does not have the key
   * @return the value, or {@code absent}
   */
  public long get(long key, long absent) {
    restart:
    while (true) {
      Root r = root;
      long[] node = r.node();
      Object[] children = r.children();
      long v = version(node);
      if (isLocked(v) || r != root) {
        Thread.onSpinWait();
        continue;
      }
      try {
        while (!isLeaf(node)) {
          int i = childIndex(node, key);
          long[] child = (long[]) children[2 * i];
          Object[] grandchildren = (Object[]) children[2 * i + 1];
          long cv = version(child);
          if (isLocked(cv) || !validate(node, v)) {
            Thread.onSpinWait();
            continue restart;
          }
          node = child;
          children = grandchildren;
          v = cv;
        }
        int pos = find(node, key);
        long value = pos >= 0 ? node[values + pos] : absent;
        if (validate(node, v)) {
          return value;
        }
      } catch (RuntimeException e) {
        // A torn read can see a null child during a split.
        if (validate(node, v)) {
          throw e;
        }
      }
    }
  }

  /**
   * Gives true if the map has {@code key}.
   *
   * @param key the key
   * @return true if the map has the key
   */
  public boolean containsKey(long key) {
    restart:
    while (true) {
      Root r = root;
      long[] node = r.node();
      Object[] children = r.children();
      long v = version(node);
      if (isLocked(v) || r != root) {
        Thread.onSpinWait();
        continue;
      }
      try {
        while (!isLeaf(node)) {
          int i = childIndex(node, key);
          long[] child = (long[]) children[2 * i];
          Object[] grandchildren = (Object[]) children[2 * i + 1];
          long cv = version(child);
          if (isLocked(cv) || !validate(node, v)) {
            Thread.onSpinWait();
            continue restart;
          }
          node = child;
          children = grandchildren;
          v = cv;
        }
        boolean found = find(node, key) >= 0;
        if (validate(node, v)) {
          return found;
        }
      } catch (RuntimeException e) {
        if (validate(node, v)) {
          throw e;
        }
      }
    }
  }

  /**
   * Looks up many keys at the same time. The lookups go down the tree one level at a time,
   * together, so that the CPU waits for the cache misses of different lookups in parallel. Each
   * level has three passes: a search of every lane, a plain load of the head of every child that
   * starts its cache miss, and then the version reads and validations. A lane that does not
   * validate falls back to {@link #get}.
   *
   * @param keys the keys to look up
   * @param results the array that receives the value of {@code keys[i]} at index {@code i}; at
   *     least as long as {@code keys}
   * @param absent the value to write for a key that the map does not have
   */
  public void getMany(long[] keys, long[] results, long absent) {
    if (results.length < keys.length) {
      throw new IllegalArgumentException("results is shorter than keys");
    }
    int lanes = Math.min(BATCH_LANES, keys.length);
    long[][] nodes = new long[lanes][];
    Object[][] childrenOf = new Object[lanes][];
    long[] versions = new long[lanes];
    long[][] next = new long[lanes][];
    Object[][] nextChildren = new Object[lanes][];
    // The sum of the loads that only start cache misses. The final check keeps the JIT from
    // removing them.
    long touched = 0;
    for (int start = 0; start < keys.length; start += lanes) {
      int n = Math.min(lanes, keys.length - start);
      Root r = root;
      long[] top = r.node();
      long tv = version(top);
      if (isLocked(tv) || r != root) {
        for (int j = 0; j < n; j++) {
          results[start + j] = get(keys[start + j], absent);
        }
        continue;
      }
      for (int j = 0; j < n; j++) {
        nodes[j] = top;
        childrenOf[j] = r.children();
        versions[j] = tv;
      }
      // All leaves are at the same depth, so the lanes reach the leaves together.
      boolean inner = !isLeaf(top);
      while (inner) {
        for (int j = 0; j < n; j++) {
          next[j] = null;
          long[] node = nodes[j];
          if (node == null) {
            continue;
          }
          try {
            int i = childIndex(node, keys[start + j]);
            Object[] children = childrenOf[j];
            next[j] = (long[]) children[2 * i];
            nextChildren[j] = (Object[]) children[2 * i + 1];
          } catch (RuntimeException e) {
            // The lookup alone repeats the read, and throws if it was not a stale read.
            next[j] = null;
          }
        }
        for (int j = 0; j < n; j++) {
          long[] child = next[j];
          touched += child != null ? child[META] & 1 : 0;
        }
        inner = false;
        for (int j = 0; j < n; j++) {
          long[] parent = nodes[j];
          long[] child = next[j];
          nodes[j] = null;
          if (child != null) {
            long cv = version(child);
            if (!isLocked(cv) && validate(parent, versions[j])) {
              nodes[j] = child;
              childrenOf[j] = nextChildren[j];
              versions[j] = cv;
              inner |= !isLeaf(child);
            }
          }
        }
      }
      for (int j = 0; j < n; j++) {
        long[] leaf = nodes[j];
        long value = absent;
        if (leaf != null && isLeaf(leaf)) {
          try {
            int pos = find(leaf, keys[start + j]);
            value = pos >= 0 ? leaf[values + pos] : absent;
          } catch (RuntimeException e) {
            nodes[j] = null;
          }
        }
        results[start + j] = value;
      }
      for (int j = 0; j < n; j++) {
        long[] leaf = nodes[j];
        if (leaf == null || !isLeaf(leaf) || !validate(leaf, versions[j])) {
          results[start + j] = get(keys[start + j], absent);
        }
      }
    }
    if (touched < 0) {
      throw new AssertionError(touched);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Writes

  /**
   * Locks and gives the leaf that holds {@code key} for an insert: the leaf has the key or has
   * room for it. Full nodes on the way split first.
   */
  private long[] lockLeafForInsert(long key) {
    restart:
    while (true) {
      Root r = root;
      long[] node = r.node();
      Object[] children = r.children();
      long v = version(node);
      if (isLocked(v) || r != root) {
        Thread.onSpinWait();
        continue;
      }
      long[] parent = null;
      Object[] parentChildren = null;
      long pv = 0;
      int slot = 0;
      // True while the descent takes the last child at each level.
      boolean rightmost = true;
      try {
        while (!isLeaf(node)) {
          if (count(node) >= innerCapacity) {
            // Split a full inner node on the way down, so that a split below always has room in
            // its parent.
            break;
          }
          int i = childIndex(node, key);
          rightmost &= i == count(node);
          long[] child = (long[]) children[2 * i];
          Object[] grandchildren = (Object[]) children[2 * i + 1];
          long cv = version(child);
          if (isLocked(cv) || !validate(node, v)) {
            Thread.onSpinWait();
            continue restart;
          }
          parent = node;
          parentChildren = children;
          pv = v;
          slot = i;
          node = child;
          children = grandchildren;
          v = cv;
        }
        if (isLeaf(node)) {
          boolean full = count(node) >= leafCapacity;
          if (full) {
            boolean present = find(node, key) >= 0;
            if (!validate(node, v)) {
              continue;
            }
            full = !present;
          }
          if (!full) {
            if (tryLock(node, v)) {
              return node;
            }
            Thread.onSpinWait();
            continue;
          }
        }
      } catch (RuntimeException e) {
        if (validate(node, v)) {
          throw e;
        }
        continue;
      }
      trySplit(r, parent, parentChildren, pv, slot, node, children, v, key, rightmost);
    }
  }

  /**
   * Splits {@code node}, if neither the node nor its parent changed since the versions that the
   * caller read. Does nothing if one of them changed: the caller starts again from the root.
   */
  private void trySplit(
      Root r,
      long[] parent,
      Object[] parentChildren,
      long pv,
      int slot,
      long[] node,
      Object[] children,
      long v,
      long key,
      boolean rightmost) {
    if (parent == null) {
      if (!tryLock(node, v)) {
        return;
      }
      if (root != r) {
        unlockUnchanged(node);
        return;
      }
      Object[] split = split(node, children, key, true);
      long[] top = newInner();
      Object[] topChildren = newChildren();
      top[K] = (long) split[0];
      setCount(top, 1);
      topChildren[0] = node;
      topChildren[1] = children;
      topChildren[2] = split[1];
      topChildren[3] = split[2];
      root = new Root(top, topChildren);
      unlock(node);
      return;
    }
    if (!tryLock(parent, pv)) {
      return;
    }
    if (!tryLock(node, v)) {
      unlockUnchanged(parent);
      return;
    }
    Object[] split = split(node, children, key, rightmost);
    insertChild(parent, parentChildren, slot, (long) split[0], (long[]) split[1], (Object[]) split[2]);
    unlock(node);
    unlock(parent);
  }

  /**
   * Splits a locked, full node, and gives {separator, right node, right pairs}. If the node is
   * the last of its level and {@code key} goes after its last key, as ascending keys do, the
   * split moves only the last entry, and leaves the left node almost full.
   */
  private Object[] split(long[] node, Object[] children, long key, boolean rightmost) {
    int n = count(node);
    if (isLeaf(node)) {
      boolean append = rightmost && key > node[K + n - 1];
      int mid = append ? n - 1 : n >>> 1;
      long[] right = newLeaf();
      System.arraycopy(node, K + mid, right, K, n - mid);
      System.arraycopy(node, values + mid, right, values, n - mid);
      setCount(right, n - mid);
      setCount(node, mid);
      return new Object[] {right[K], right, null};
    }
    boolean append = rightmost && key >= node[K + n - 1];
    int mid = append ? n - 2 : n >>> 1;
    long separator = node[K + mid];
    long[] right = newInner();
    Object[] rightChildren = newChildren();
    System.arraycopy(node, K + mid + 1, right, K, n - mid - 1);
    System.arraycopy(children, 2 * (mid + 1), rightChildren, 0, 2 * (n - mid));
    setCount(right, n - mid - 1);
    setCount(node, mid);
    java.util.Arrays.fill(children, 2 * (mid + 1), 2 * (n + 1), null);
    return new Object[] {separator, right, rightChildren};
  }

  /** Puts {@code separator} at index {@code slot} of a locked inner node, with its right child. */
  private static void insertChild(
      long[] node, Object[] children, int slot, long separator, long[] right, Object[] rightPairs) {
    int n = count(node);
    System.arraycopy(node, K + slot, node, K + slot + 1, n - slot);
    System.arraycopy(children, 2 * (slot + 1), children, 2 * (slot + 2), 2 * (n - slot));
    node[K + slot] = separator;
    children[2 * (slot + 1)] = right;
    children[2 * (slot + 1) + 1] = rightPairs;
    setCount(node, n + 1);
  }

  private long put(long key, long value, long absent, boolean onlyIfAbsent) {
    long[] leaf = lockLeafForInsert(key);
    int n = count(leaf);
    int pos = find(leaf, key);
    if (pos >= 0) {
      long old = leaf[values + pos];
      if (onlyIfAbsent) {
        unlockUnchanged(leaf);
      } else {
        leaf[values + pos] = value;
        unlock(leaf);
      }
      return old;
    }
    int at = -pos - 1;
    System.arraycopy(leaf, K + at, leaf, K + at + 1, n - at);
    System.arraycopy(leaf, values + at, leaf, values + at + 1, n - at);
    leaf[K + at] = key;
    leaf[values + at] = value;
    setCount(leaf, n + 1);
    unlock(leaf);
    size.increment();
    return absent;
  }

  /**
   * Sets the value of {@code key}.
   *
   * @param key the key
   * @param value the value
   * @param absent the value to give if the map did not have the key
   * @return the old value, or {@code absent}
   */
  public long put(long key, long value, long absent) {
    return put(key, value, absent, false);
  }

  /**
   * Sets the value of {@code key} if the map does not have the key.
   *
   * @param key the key
   * @param value the value
   * @param absent the value to give if the map did not have the key
   * @return the current value, or {@code absent} if the map did not have the key
   */
  public long putIfAbsent(long key, long value, long absent) {
    return put(key, value, absent, true);
  }

  /**
   * Removes {@code key}.
   *
   * @param key the key
   * @param absent the value to give if the map did not have the key
   * @return the old value, or {@code absent}
   */
  public long remove(long key, long absent) {
    restart:
    while (true) {
      Root r = root;
      long[] node = r.node();
      Object[] children = r.children();
      long v = version(node);
      if (isLocked(v) || r != root) {
        Thread.onSpinWait();
        continue;
      }
      try {
        while (!isLeaf(node)) {
          int i = childIndex(node, key);
          long[] child = (long[]) children[2 * i];
          Object[] grandchildren = (Object[]) children[2 * i + 1];
          long cv = version(child);
          if (isLocked(cv) || !validate(node, v)) {
            Thread.onSpinWait();
            continue restart;
          }
          node = child;
          children = grandchildren;
          v = cv;
        }
        if (find(node, key) < 0) {
          // An absent key writes no shared memory.
          if (validate(node, v)) {
            return absent;
          }
          continue;
        }
        if (!tryLock(node, v)) {
          Thread.onSpinWait();
          continue;
        }
      } catch (RuntimeException e) {
        if (validate(node, v)) {
          throw e;
        }
        continue;
      }
      int n = count(node);
      int pos = find(node, key);
      long old = node[values + pos];
      System.arraycopy(node, K + pos + 1, node, K + pos, n - pos - 1);
      System.arraycopy(node, values + pos + 1, node, values + pos, n - pos - 1);
      setCount(node, n - 1);
      unlock(node);
      size.decrement();
      return old;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Scans

  /**
   * Gives each entry with a key in {@code [from, to)} to {@code action}, in key order, while the
   * action returns {@code true}.
   *
   * @param from the inclusive lower bound
   * @param to the exclusive upper bound
   * @param action the action; it returns {@code false} to stop
   */
  public void forEachInRangeWhile(long from, long to, EntryPredicate action) {
    if (from < to) {
      scan(from, to - 1, action);
    }
  }

  /**
   * Gives each entry with a key equal to or larger than {@code from} to {@code action}, in key
   * order, while the action returns {@code true}.
   *
   * @param from the inclusive lower bound
   * @param action the action; it returns {@code false} to stop
   */
  public void forEachFrom(long from, EntryPredicate action) {
    scan(from, Long.MAX_VALUE, action);
  }

  /**
   * Gives the entries with a key in {@code [from, last]} to {@code action}. Each step finds the
   * leaf of the next key from the root, copies its entries with an optimistic read, and gives them
   * to the action only after the read is valid. It is weakly consistent.
   */
  private void scan(long from, long last, EntryPredicate action) {
    java.util.Objects.requireNonNull(action);
    long[] keys = new long[leafCapacity];
    long[] vals = new long[leafCapacity];
    long next = from;
    restart:
    while (true) {
      Root r = root;
      long[] node = r.node();
      Object[] children = r.children();
      long v = version(node);
      if (isLocked(v) || r != root) {
        Thread.onSpinWait();
        continue;
      }
      // The smallest key of the leaves after this one, from the separators on the way down.
      boolean bounded = false;
      long upper = 0;
      int m = 0;
      try {
        while (!isLeaf(node)) {
          int i = childIndex(node, next);
          if (i < Math.min(count(node), innerCapacity)) {
            bounded = true;
            upper = node[K + i];
          }
          long[] child = (long[]) children[2 * i];
          Object[] grandchildren = (Object[]) children[2 * i + 1];
          long cv = version(child);
          if (isLocked(cv) || !validate(node, v)) {
            Thread.onSpinWait();
            continue restart;
          }
          node = child;
          children = grandchildren;
          v = cv;
        }
        int n = Math.min(count(node), leafCapacity);
        for (int i = below(node, next); i < n; i++) {
          long key = node[K + i];
          if (key > last) {
            break;
          }
          keys[m] = key;
          vals[m] = node[values + i];
          m++;
        }
      } catch (RuntimeException e) {
        if (validate(node, v)) {
          throw e;
        }
        continue;
      }
      if (!validate(node, v)) {
        continue;
      }
      for (int i = 0; i < m; i++) {
        if (!action.test(keys[i], vals[i])) {
          return;
        }
      }
      if (!bounded || upper > last) {
        return;
      }
      next = upper;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Size and test support

  /**
   * Gives the number of entries. It is exact when no write runs at the same time.
   *
   * @return the number of entries
   */
  public int size() {
    long n = size.sum();
    return n <= 0 ? 0 : (int) Math.min(n, Integer.MAX_VALUE);
  }

  /**
   * Gives true if the map has no entries.
   *
   * @return true if the map is empty
   */
  public boolean isEmpty() {
    return size() == 0;
  }

  /** Gives the number of levels of the tree. */
  int height() {
    int h = 1;
    Root r = root;
    long[] node = r.node();
    Object[] children = r.children();
    while (!isLeaf(node)) {
      long[] child = (long[]) children[0];
      children = (Object[]) children[1];
      node = child;
      h++;
    }
    return h;
  }

  /**
   * Checks the structure of the tree and gives the number of entries. Call it only when no write
   * runs.
   *
   * @throws AssertionError if the tree is not valid
   */
  int checkInvariants() {
    Root r = root;
    int[] leafDepth = {-1};
    int entries = check(r.node(), r.children(), Long.MIN_VALUE, false, Long.MAX_VALUE, false, 0,
        leafDepth);
    if (entries != size()) {
      throw new AssertionError("size " + size() + " but " + entries + " entries");
    }
    return entries;
  }

  /** Checks a node whose keys are in [low, high), where a missing bound is open. */
  private int check(
      long[] node,
      Object[] children,
      long low,
      boolean hasLow,
      long high,
      boolean hasHigh,
      int depth,
      int[] leafDepth) {
    check(!isLocked(version(node)), "a node is locked");
    int n = count(node);
    boolean leaf = isLeaf(node);
    check(n >= 0 && n <= (leaf ? leafCapacity : innerCapacity), "count out of range: " + n);
    check(node.length == (leaf ? K + 2 * leafCapacity : K + innerCapacity), "wrong node length");
    for (int i = 0; i < n; i++) {
      long k = node[K + i];
      check(i == 0 || node[K + i - 1] < k, "keys out of order at " + i);
      check(!hasLow || low <= k, "key below the lower bound");
      check(!hasHigh || k < high, "key above the upper bound");
    }
    if (leaf) {
      check(children == null, "a leaf has pairs");
      if (leafDepth[0] < 0) {
        leafDepth[0] = depth;
      }
      check(leafDepth[0] == depth, "leaves at different depths");
      return n;
    }
    check(n > 0 || depth == 0, "an empty inner node below the root");
    check(children != null && children.length == 2 * (innerCapacity + 1), "wrong pairs");
    for (int i = n + 1; i <= innerCapacity; i++) {
      check(children[2 * i] == null && children[2 * i + 1] == null, "stale child at " + i);
    }
    int entries = 0;
    for (int i = 0; i <= n; i++) {
      long[] child = (long[]) children[2 * i];
      check(child != null, "null child at " + i);
      Object[] grandchildren = (Object[]) children[2 * i + 1];
      boolean childHasLow = i > 0 || hasLow;
      long childLow = i > 0 ? node[K + i - 1] : low;
      boolean childHasHigh = i < n || hasHigh;
      long childHigh = i < n ? node[K + i] : high;
      entries +=
          check(child, grandchildren, childLow, childHasLow, childHigh, childHasHigh, depth + 1,
              leafDepth);
    }
    return entries;
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
