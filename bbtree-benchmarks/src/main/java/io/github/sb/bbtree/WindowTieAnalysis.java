package io.github.sb.bbtree;

import io.github.sb.bbtree.bench.KeySets;
import java.util.Locale;

/**
 * Measures how often a narrower window would tie, for the review of a vector search on Apple
 * silicon. It builds a map of 1M keys of each type and walks every node. For each width of 2, 4
 * and 8 bytes, it counts the keys whose window, cut to that width, equals the cut window of a
 * neighbor in the same node. A lookup of such a key compares key objects. For leaves of
 * {@code long} keys, it also counts the leaves where the cut window still reaches the end of the
 * key, where a tie is a match and no key object is read.
 *
 * <p>Run it with {@code java -cp benchmarks.jar io.github.sb.bbtree.WindowTieAnalysis}.
 */
public final class WindowTieAnalysis {

  private static final int[] WIDTHS = {2, 4, 8};

  private WindowTieAnalysis() {}

  public static void main(String[] args) {
    int size = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
    System.out.println(
        "keys    level  slots      tie@2B   tie@4B   tie@8B   exact@2B exact@4B exact@8B");
    for (String keyType : new String[] {"long", "uuid4", "uuid7", "string"}) {
      BBTreeMap<Object, Object> map = KeySets.filledMap(keyType, size);
      int fixed = keyType.equals("long") ? 8 : keyType.startsWith("uuid") ? 16 : -1;
      long[][] stats = new long[2][1 + 2 * WIDTHS.length];
      walk(map.rootNode(), fixed, stats);
      for (int level = 0; level < 2; level++) {
        long[] s = stats[level];
        StringBuilder line =
            new StringBuilder(
                String.format(Locale.ROOT, "%-7s %-6s %-10d", keyType, level == 0 ? "inner" : "leaf", s[0]));
        for (int w = 0; w < WIDTHS.length; w++) {
          line.append(String.format(Locale.ROOT, " %7.2f%%", 100.0 * s[1 + w] / s[0]));
        }
        for (int w = 0; w < WIDTHS.length; w++) {
          line.append(
              fixed < 0 ? "        -" : String.format(Locale.ROOT, " %7.2f%%", 100.0 * s[1 + WIDTHS.length + w] / s[0]));
        }
        System.out.println(line);
      }
    }
  }

  /**
   * Adds the counts of a subtree: {@code stats[level][0]} slots, then the tied slots for each
   * width, then the slots in nodes where the cut window reaches the end of a fixed-length key.
   */
  private static void walk(Node node, int fixed, long[][] stats) {
    int level = node instanceof Leaf ? 1 : 0;
    long[] s = stats[level];
    int n = node.count;
    s[0] += n;
    for (int w = 0; w < WIDTHS.length; w++) {
      int bits = WIDTHS[w] * 8;
      for (int i = 0; i < n; i++) {
        long cut = cut(node.windows[i], bits);
        boolean tie =
            (i > 0 && cut(node.windows[i - 1], bits) == cut)
                || (i + 1 < n && cut(node.windows[i + 1], bits) == cut);
        if (tie) {
          s[1 + w]++;
        }
      }
      if (fixed > 0 && node.prefix + WIDTHS[w] >= fixed) {
        s[1 + WIDTHS.length + w] += n;
      }
    }
    if (node instanceof Inner inner) {
      for (int i = 0; i <= n; i++) {
        walk(inner.children[i], fixed, stats);
      }
    }
  }

  /** Keeps the first {@code bits} bits of a stored window. */
  private static long cut(long window, int bits) {
    return bits == 64 ? window : window >> (64 - bits);
  }
}
