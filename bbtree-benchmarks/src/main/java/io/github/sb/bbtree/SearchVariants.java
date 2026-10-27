package io.github.sb.bbtree;

/**
 * Maps with different node searches, for {@link CounterProbe} and docs/node-search-cpus.md. The
 * name of a variant is one of {@code scalar}, {@code count}, {@code scan}, {@code branchless} and
 * {@code binary}, with the suffix {@code -narrow} for 4-byte inner windows. Run the class to check
 * each variant against a {@link java.util.TreeMap}.
 */
public final class SearchVariants {

  private SearchVariants() {}

  /** A count of all live windows with no branch on the data. */
  static final WindowScan BRANCHLESS =
      new WindowScan() {
        @Override
        public long scan(long[] w, int n, long t) {
          int lt = 0;
          int eq = 0;
          for (int i = 0; i < n; i++) {
            long x = w[i];
            lt += x < t ? 1 : 0;
            eq += x == t ? 1 : 0;
          }
          return WindowScan.pack(lt, lt + eq);
        }

        @Override
        public long scan(int[] w, int n, int t) {
          int lt = 0;
          int eq = 0;
          for (int i = 0; i < n; i++) {
            int x = w[i];
            lt += x < t ? 1 : 0;
            eq += x == t ? 1 : 0;
          }
          return WindowScan.pack(lt, lt + eq);
        }
      };

  /**
   * A binary search with no branch on the data: each step moves the base with a conditional
   * select. Then a scan for equal windows, which runs 0 or 1 steps for distinct windows.
   */
  static final WindowScan BINARY =
      new WindowScan() {
        @Override
        public long scan(long[] w, int n, long t) {
          int base = 0;
          int len = n;
          while (len > 1) {
            int half = len >>> 1;
            base = w[base + half - 1] < t ? base + half : base;
            len -= half;
          }
          int lo = base + (len == 1 && w[base] < t ? 1 : 0);
          int hi = lo;
          while (hi < n && w[hi] == t) {
            hi++;
          }
          return WindowScan.pack(lo, hi);
        }

        @Override
        public long scan(int[] w, int n, int t) {
          int base = 0;
          int len = n;
          while (len > 1) {
            int half = len >>> 1;
            base = w[base + half - 1] < t ? base + half : base;
            len -= half;
          }
          int lo = base + (len == 1 && w[base] < t ? 1 : 0);
          int hi = lo;
          while (hi < n && w[hi] == t) {
            hi++;
          }
          return WindowScan.pack(lo, hi);
        }
      };

  /**
   * Makes an empty map with the default capacities and the named node search.
   *
   * @param inlineKey the order and the encoding of the keys
   * @param variant the name of the search, for example {@code binary-narrow}
   * @param <K> the key type
   * @param <V> the value type
   * @return the map
   */
  public static <K, V> BBTreeMap<K, V> create(InlineKey<K> inlineKey, String variant) {
    boolean narrow = variant.endsWith("-narrow");
    String name = narrow ? variant.substring(0, variant.length() - 7) : variant;
    WindowScan scan =
        switch (name) {
          case "scalar" -> WindowScan.SCALAR;
          case "count" -> VectorWindowScan.COUNT;
          case "scan" -> VectorWindowScan.SCAN;
          case "branchless" -> BRANCHLESS;
          case "binary" -> BINARY;
          default -> throw new IllegalArgumentException(variant);
        };
    return new BBTreeMap<>(
        inlineKey,
        inlineKey,
        BBTreeMap.DEFAULT_LEAF_CAPACITY,
        BBTreeMap.DEFAULT_INNER_CAPACITY,
        scan,
        narrow);
  }

  /**
   * Checks each variant against a TreeMap.
   *
   * @param args not used
   */
  public static void main(String[] args) {
    String[] variants = {
      "scalar", "count", "scan", "branchless", "binary", "count-narrow", "binary-narrow"
    };
    for (String v : variants) {
      java.util.SplittableRandom r = new java.util.SplittableRandom(1);
      BBTreeMap<Long, Long> m = create(InlineKey.LONG, v);
      java.util.TreeMap<Long, Long> t = new java.util.TreeMap<>();
      for (int i = 0; i < 2_000_000; i++) {
        // Half of the keys tie on their first 4 bytes.
        long k =
            r.nextBoolean() ? r.nextLong(200_000) : (long) r.nextInt(3) << 32 | r.nextInt(100_000);
        int d = r.nextInt(10);
        Object a = d < 5 ? m.put(k, k) : d < 7 ? m.remove(k) : m.get(k);
        Object b = d < 5 ? t.put(k, k) : d < 7 ? t.remove(k) : t.get(k);
        if (!java.util.Objects.equals(a, b)) {
          throw new AssertionError(v + " op " + i);
        }
      }
      if (m.checkInvariants() != t.size()) {
        throw new AssertionError(v + " size");
      }
      System.out.println(v + " ok, " + t.size() + " keys, height " + m.height());
    }
  }
}
