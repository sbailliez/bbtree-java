package io.github.sb.bbtree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;

/**
 * Measures how the memory order of the nodes changes the lookup time. It builds a {@link
 * LongLongBBTreeMap} of 1M random keys, copies its nodes in a chosen order, and compares lookups
 * in the copy with lookups in the map. The copy has the same layout, the same keys arrays and the
 * same lookup code: only the addresses of the nodes differ.
 *
 * <p>Orders: {@code dfs} (a node, then its subtrees), {@code groups} (the upper inner nodes first,
 * then each bottom inner node followed by its leaves, in key order), {@code leaves} (all inner
 * nodes first, then the leaves in key order), {@code shuffled} (all inner nodes first, then the
 * leaves in random order), {@code bfs} (level by level).
 *
 * <p>Arguments: the order, the mode ({@code hit-cold} or {@code miss-cold}), optionally the
 * number of lookups, and optionally {@code churn}: garbage after the copy, so that young
 * collections copy the new nodes.
 */
public final class NodeOrderProbe {

  private NodeOrderProbe() {}

  /** Copies one node with no children yet. */
  private static Node copyOne(Node node) {
    if (node instanceof Leaf leaf) {
      Leaf c = new Leaf(leaf.capacity(), true, true);
      System.arraycopy(leaf.longValues, 0, c.longValues, 0, leaf.count);
      fill(leaf, c);
      return c;
    }
    Inner inner = (Inner) node;
    Inner c = new Inner(inner.capacity(), true, false);
    fill(inner, c);
    return c;
  }

  private static void fill(Node from, Node to) {
    System.arraycopy(from.windows, 0, to.windows, 0, from.count);
    System.arraycopy(from.keys, 0, to.keys, 0, from.count);
    to.count = from.count;
    to.prefix = from.prefix;
    to.low = from.low;
    to.high = from.high;
  }

  private static void collect(Node node, int depth, List<List<Node>> levels) {
    while (levels.size() <= depth) {
      levels.add(new ArrayList<>());
    }
    levels.get(depth).add(node);
    if (node instanceof Inner inner) {
      for (int i = 0; i <= inner.count; i++) {
        collect(inner.children[i], depth + 1, levels);
      }
    }
  }

  private static void dfs(Node node, List<Node> out) {
    out.add(node);
    if (node instanceof Inner inner) {
      for (int i = 0; i <= inner.count; i++) {
        dfs(inner.children[i], out);
      }
    }
  }

  /** Gives the nodes in the order of their copy. */
  private static List<Node> order(Node root, String order) {
    List<List<Node>> levels = new ArrayList<>();
    collect(root, 0, levels);
    List<Node> leaves = levels.get(levels.size() - 1);
    List<Node> out = new ArrayList<>();
    switch (order) {
      case "dfs" -> dfs(root, out);
      case "bfs" -> levels.forEach(out::addAll);
      case "leaves", "shuffled" -> {
        for (int d = 0; d < levels.size() - 1; d++) {
          out.addAll(levels.get(d));
        }
        List<Node> l = new ArrayList<>(leaves);
        if (order.equals("shuffled")) {
          Collections.shuffle(l, new Random(4));
        }
        out.addAll(l);
      }
      case "groups" -> {
        for (int d = 0; d < levels.size() - 2; d++) {
          out.addAll(levels.get(d));
        }
        for (Node bottom : levels.get(levels.size() - 2)) {
          out.add(bottom);
          Inner inner = (Inner) bottom;
          for (int i = 0; i <= inner.count; i++) {
            out.add(inner.children[i]);
          }
        }
      }
      default -> throw new IllegalArgumentException("unknown order " + order);
    }
    return out;
  }

  /** Copies the tree with the nodes allocated in the order of {@code nodes}. */
  private static Node copy(Node root, List<Node> nodes) {
    Map<Node, Node> copies = new IdentityHashMap<>();
    for (Node node : nodes) {
      copies.put(node, copyOne(node));
    }
    for (Node node : nodes) {
      if (node instanceof Inner inner) {
        Inner c = (Inner) copies.get(node);
        for (int i = 0; i <= inner.count; i++) {
          c.children[i] = copies.get(inner.children[i]);
        }
      }
    }
    return copies.get(root);
  }

  private static long longWindow(long key, int prefix) {
    long encoded = key ^ Long.MIN_VALUE;
    return (prefix >= Long.BYTES ? 0L : encoded << (prefix << 3)) ^ Long.MIN_VALUE;
  }

  /** The lookup of {@link LongLongBBTreeMap#get}, on any root. */
  static long get(Node root, long key, long absent) {
    restart:
    while (true) {
      Node node = root;
      long v = node.version();
      while (true) {
        long[] w = node.windows;
        int n = Math.min(node.count, w.length);
        long target = longWindow(key, node.prefix);
        int lo = 0;
        while (lo < n && w[lo] < target) {
          lo++;
        }
        if (node instanceof Leaf leaf) {
          long value = lo < n && w[lo] == target ? leaf.longValues[lo] : absent;
          if (leaf.validate(v)) {
            return value;
          }
          continue restart;
        }
        Inner inner = (Inner) node;
        Node next = inner.children[lo < n && w[lo] == target ? lo + 1 : lo];
        long cv = next.version();
        if (Node.isLocked(cv) || !inner.validate(v)) {
          continue restart;
        }
        node = next;
        v = cv;
      }
    }
  }

  private static long gcCount() {
    long count = 0;
    for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
      count += gc.getCollectionCount();
    }
    return count;
  }

  /**
   * Runs the comparison.
   *
   * @param args the order, the mode, and optionally the number of lookups
   */
  public static void main(String[] args) {
    String order = args[0];
    String mode = args[1];
    int ops = Integer.parseInt(args.length > 2 ? args[2] : "4000000");
    int n = 1_000_000;
    SplittableRandom random = new SplittableRandom(3);
    long[] keys = new long[n];
    long collectionsBefore = gcCount();
    LongLongBBTreeMap map = new LongLongBBTreeMap();
    boolean prebox = args.length > 3 && args[3].equals("prebox");
    Long[] boxed = new Long[n];
    for (int i = 0; i < n; i++) {
      keys[i] = random.nextLong();
      boxed[i] = keys[i];
    }
    for (int i = 0; i < n; i++) {
      if (prebox) {
        // The keys were boxed before the build, so the build allocates only nodes and the
        // temporary arrays of the splits.
        map.asMap().put(boxed[i], (long) i);
      } else {
        map.put(keys[i], i, -1);
      }
    }
    System.out.printf("build: %d collections%n", gcCount() - collectionsBefore);
    Node original = map.asMap().rootNode();
    Node copied = copy(original, order(original, order));
    if (args.length > 3 && args[3].equals("churn")) {
      // Allocate short-lived garbage, so that young collections copy the new nodes.
      long collections = gcCount();
      long garbage = 0;
      for (int i = 0; i < 16_000; i++) {
        garbage += new byte[1 << 20].length;
      }
      System.out.printf(
          "churn: %d MB of garbage, %d collections%n", garbage >> 20, gcCount() - collections);
    }
    long[] lookups = new long[ops];
    SplittableRandom pick = new SplittableRandom(7);
    for (int i = 0; i < ops; i++) {
      lookups[i] = mode.startsWith("miss") ? pick.nextLong() : keys[pick.nextInt(n)];
    }
    for (int i = 0; i < ops; i += 997) {
      if (get(copied, lookups[i], -1) != map.get(lookups[i], -1)) {
        throw new AssertionError("different value for key " + lookups[i]);
      }
    }
    String only = System.getProperty("only");
    if (only != null) {
      // For perf stat: print READY, wait, then run lookups on one of the two trees only.
      Node tree = only.equals("map") ? original : copied;
      long sink = 0;
      for (long k : lookups) {
        sink += get(tree, k, -1);
      }
      System.out.println("READY");
      System.out.flush();
      try {
        Thread.sleep(1500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      long t0 = System.nanoTime();
      for (int round = 0; round < 3; round++) {
        for (long k : lookups) {
          sink += get(tree, k, -1);
        }
      }
      System.out.printf(
          "%s: %.1f ns (%d)%n", only, (System.nanoTime() - t0) / (3.0 * ops), sink & 1);
      return;
    }
    long sink = 0;
    double[] before = new double[5];
    double[] after = new double[5];
    for (int round = 0; round < 8; round++) {
      long t0 = System.nanoTime();
      for (long k : lookups) {
        sink += get(original, k, -1);
      }
      long t1 = System.nanoTime();
      for (long k : lookups) {
        sink += get(copied, k, -1);
      }
      long t2 = System.nanoTime();
      if (round >= 3) {
        before[round - 3] = (double) (t1 - t0) / ops;
        after[round - 3] = (double) (t2 - t1) / ops;
      }
    }
    Arrays.sort(before);
    Arrays.sort(after);
    System.out.printf(
        "%-8s %-9s: map %.1f ns, copy %.1f ns, %+.0f%% (%d)%n",
        order, mode, before[2], after[2], (after[2] / before[2] - 1) * 100, sink & 1);
  }
}
