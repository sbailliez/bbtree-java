package io.github.sb.bbtree;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * A read-only prototype of a node layout with fewer objects, to measure its gain before a rewrite
 * of the map. It copies a finished {@link LongLongBBTreeMap} into the new layout, and then compares
 * lookups in the two layouts on the same keys.
 *
 * <p>In the new layout, a node is two arrays and no node object:
 *
 * <ul>
 *   <li>a {@code long[]} head: slot 0 is the version, slot 1 has the count, the prefix and the
 *       kind of node, and the windows start at slot 2. The version and the first windows share a
 *       cache line.
 *   <li>a body: for an inner node, an {@code Object[]} of pairs (child head, child body); for a
 *       leaf of primitive values, the {@code long[]} of values.
 * </ul>
 *
 * <p>A lookup level loads the pair of the child from the parent body, then the head of the child.
 * The current layout loads the child reference, then the child object, then its windows. The
 * prototype reads the version with acquire loads and validates each level as the map does.
 *
 * <p>Arguments: the mode ({@code hit-cold}, {@code miss-cold} or {@code hit-warm}), and
 * optionally the number of lookups.
 */
public final class FlatLayoutProbe {

  private static final VarHandle VERSION = MethodHandles.arrayElementVarHandle(long[].class);
  private static final int HEAD = 2;
  private static final long LEAF = 1L << 62;

  private FlatLayoutProbe() {}

  /** The head and body of the root. */
  private Object rootHead;

  private Object rootBody;

  /** Copies the tree under {@code node} into the new layout, and gives {head, body}. */
  private static Object[] copy(Node node) {
    int n = node.count;
    long[] head = new long[HEAD + node.capacity()];
    head[0] = 0;
    head[1] = (long) n | ((long) node.prefix << 32) | (node instanceof Leaf ? LEAF : 0);
    System.arraycopy(node.windows, 0, head, HEAD, n);
    if (node instanceof Leaf leaf) {
      return new Object[] {head, Arrays.copyOf(leaf.longValues, leaf.capacity())};
    }
    Inner inner = (Inner) node;
    Object[] body = new Object[2 * inner.children.length];
    for (int i = 0; i <= n; i++) {
      Object[] child = copy(inner.children[i]);
      body[2 * i] = child[0];
      body[2 * i + 1] = child[1];
    }
    return new Object[] {head, body};
  }

  private static long longWindow(long key, int prefix) {
    long encoded = key ^ Long.MIN_VALUE;
    return (prefix >= Long.BYTES ? 0L : encoded << (prefix << 3)) ^ Long.MIN_VALUE;
  }

  /** The linear scan of the map, over the windows from slot {@code HEAD}. */
  private static int countBelow(long[] head, int n, long target) {
    int i = HEAD;
    int end = HEAD + n;
    while (i < end && head[i] < target) {
      i++;
    }
    return i - HEAD;
  }

  /** The root of a copy of the current layout, made in the same order as the new layout. */
  private Node copiedRoot;

  /** Copies the tree under {@code node} in the current layout, in depth-first order. */
  private static Node copyNodes(Node node) {
    if (node instanceof Leaf leaf) {
      Leaf c = new Leaf(leaf.capacity(), true, true);
      System.arraycopy(leaf.windows, 0, c.windows, 0, leaf.count);
      System.arraycopy(leaf.longValues, 0, c.longValues, 0, leaf.count);
      c.count = leaf.count;
      c.prefix = leaf.prefix;
      return c;
    }
    Inner inner = (Inner) node;
    Inner c = new Inner(inner.capacity(), true, false);
    System.arraycopy(inner.windows, 0, c.windows, 0, inner.count);
    c.count = inner.count;
    c.prefix = inner.prefix;
    for (int i = 0; i <= inner.count; i++) {
      c.children[i] = copyNodes(inner.children[i]);
    }
    return c;
  }

  /** The linear scan of the map over the windows of a node of the current layout. */
  private static int countBelow(long[] w, int n, long target, boolean unused) {
    int i = 0;
    while (i < n && w[i] < target) {
      i++;
    }
    return i;
  }

  /**
   * Looks up {@code key} in the copy of the current layout, with the same code shape as {@link
   * #get}, so that only the layout differs.
   */
  long getNodes(long key, long absent) {
    restart:
    while (true) {
      Node node = copiedRoot;
      long v = node.version();
      while (true) {
        long[] w = node.windows;
        int n = Math.min(node.count, w.length);
        long target = longWindow(key, node.prefix);
        int lo = countBelow(w, n, target, true);
        if (node instanceof Leaf leaf) {
          boolean found = lo < n && w[lo] == target;
          long value = found ? leaf.longValues[lo] : absent;
          if (leaf.validate(v)) {
            return value;
          }
          continue restart;
        }
        Inner inner = (Inner) node;
        int child = lo < n && w[lo] == target ? lo + 1 : lo;
        Node next = inner.children[child];
        long cv = next.version();
        if (Node.isLocked(cv) || !inner.validate(v)) {
          continue restart;
        }
        node = next;
        v = cv;
      }
    }
  }

  /** Looks up {@code key} as {@link LongLongBBTreeMap#get} does, in the new layout. */
  long get(long key, long absent) {
    restart:
    while (true) {
      long[] head = (long[]) rootHead;
      Object body = rootBody;
      long v = (long) VERSION.getAcquire(head, 0);
      try {
        while (true) {
          long meta = head[1];
          int n = Math.min((int) meta, head.length - HEAD);
          int prefix = (int) (meta >>> 32) & 0xFF;
          int lo = countBelow(head, n, longWindow(key, prefix));
          if ((meta & LEAF) != 0) {
            boolean found = lo < n && head[HEAD + lo] == longWindow(key, prefix);
            long value = found ? ((long[]) body)[lo] : absent;
            VarHandle.acquireFence();
            if ((long) VERSION.getAcquire(head, 0) == v) {
              return value;
            }
            continue restart;
          }
          // An inner node: lo windows are smaller, and an equal window goes right.
          int child = lo < n && head[HEAD + lo] == longWindow(key, prefix) ? lo + 1 : lo;
          Object[] pairs = (Object[]) body;
          long[] childHead = (long[]) pairs[2 * child];
          Object childBody = pairs[2 * child + 1];
          long cv = (long) VERSION.getAcquire(childHead, 0);
          VarHandle.acquireFence();
          if ((cv & 1) != 0 || (long) VERSION.getAcquire(head, 0) != v) {
            continue restart;
          }
          head = childHead;
          body = childBody;
          v = cv;
        }
      } catch (RuntimeException e) {
        // A torn read; the prototype has no writers, so this does not happen.
        throw e;
      }
    }
  }

  /**
   * Runs the comparison.
   *
   * @param args the mode and optionally the number of lookups
   */
  public static void main(String[] args) {
    String mode = args[0];
    int ops = Integer.parseInt(args.length > 1 ? args[1] : "4000000");
    int n = 1_000_000;
    SplittableRandom random = new SplittableRandom(3);
    long[] keys = new long[n];
    LongLongBBTreeMap map = new LongLongBBTreeMap();
    for (int i = 0; i < n; i++) {
      keys[i] = random.nextLong();
      map.put(keys[i], i, -1);
    }
    FlatLayoutProbe flat = new FlatLayoutProbe();
    Object[] root = copy(map.asMap().rootNode());
    flat.rootHead = root[0];
    flat.rootBody = root[1];
    flat.copiedRoot = copyNodes(map.asMap().rootNode());
    long[] lookups = new long[ops];
    SplittableRandom pick = new SplittableRandom(7);
    for (int i = 0; i < ops; i++) {
      lookups[i] = mode.startsWith("miss") ? pick.nextLong() : keys[pick.nextInt(n)];
    }
    if (mode.endsWith("warm")) {
      // Keys in sorted order, so that consecutive lookups share nodes in the cache.
      Arrays.sort(lookups);
    }
    // The two layouts must give the same values.
    for (int i = 0; i < ops; i += 997) {
      long want = map.get(lookups[i], -1);
      if (flat.get(lookups[i], -1) != want || flat.getNodes(lookups[i], -1) != want) {
        throw new AssertionError("different value for key " + lookups[i]);
      }
    }
    long sink = 0;
    double[] current = new double[5];
    double[] copied = new double[5];
    double[] flatTimes = new double[5];
    for (int round = 0; round < 8; round++) {
      long t0 = System.nanoTime();
      for (long k : lookups) {
        sink += map.get(k, -1);
      }
      long t1 = System.nanoTime();
      for (long k : lookups) {
        sink += flat.get(k, -1);
      }
      long t2 = System.nanoTime();
      for (long k : lookups) {
        sink += flat.getNodes(k, -1);
      }
      long t3 = System.nanoTime();
      if (round >= 3) {
        current[round - 3] = (double) (t1 - t0) / ops;
        flatTimes[round - 3] = (double) (t2 - t1) / ops;
        copied[round - 3] = (double) (t3 - t2) / ops;
      }
    }
    Arrays.sort(current);
    Arrays.sort(flatTimes);
    Arrays.sort(copied);
    System.out.printf(
        "%s: current %.1f ns, current copied %.1f ns, flat %.1f ns, flat against copied %+.0f%%"
            + " (%d)%n",
        mode,
        current[2],
        copied[2],
        flatTimes[2],
        (flatTimes[2] / copied[2] - 1) * 100,
        sink & 1);
  }
}
