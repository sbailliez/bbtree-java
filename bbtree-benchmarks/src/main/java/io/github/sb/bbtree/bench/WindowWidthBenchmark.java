package io.github.sb.bbtree.bench;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * One node search over windows of 8, 4 or 2 bytes, for the review of a vector search on Apple
 * silicon. It does not use the map.
 *
 * <p>A node holds {@code capacity} slots, of which three quarters are live. As in the Rust
 * bbtree (invariant 4), the slots after the live ones hold the largest window, so a search can
 * compare the full capacity with no mask. The windows are stored with the sign bit flipped, so a
 * signed compare gives their unsigned order.
 *
 * <ul>
 *   <li>{@code scalar}: the linear scan of the map, which stops at the first window that is not
 *       smaller than the target.
 *   <li>{@code vector}: the NEON count of the Rust bbtree, in the Vector API: compare each
 *       register with the target, subtract the all-ones lanes into counters, and add the lanes
 *       once at the end.
 * </ul>
 *
 * <p>With {@code nodes = 256}, the nodes stay in the L1 cache. With {@code nodes = 262144}, they
 * take 32 to 128 MB, and most searches start with a cache miss, as in a large tree.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms4g", "-Xmx4g", "--add-modules=jdk.incubator.vector"})
public class WindowWidthBenchmark {

  private static final VectorSpecies<Long> LONGS = LongVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;
  private static final VectorSpecies<Short> SHORTS = ShortVector.SPECIES_PREFERRED;

  @Param({"64", "32", "16"})
  public int width;

  @Param({"scalar", "vector"})
  public String search;

  @Param({"32", "64"})
  public int capacity;

  @Param({"256", "262144"})
  public int nodes;

  private boolean vector;
  private long[][] longNodes;
  private int[][] intNodes;
  private short[][] shortNodes;
  private long[] targets;
  private int live;
  private int[] order;
  private int next;

  @Setup
  public void setUp() {
    SplittableRandom random = new SplittableRandom(13);
    live = capacity * 3 / 4;
    vector = search.equals("vector");
    targets = new long[1 << 16];
    for (int i = 0; i < targets.length; i++) {
      targets[i] = random.nextLong();
    }
    order = new int[1 << 16];
    for (int i = 0; i < order.length; i++) {
      order[i] = random.nextInt(nodes);
    }
    switch (width) {
      case 64 -> {
        longNodes = new long[nodes][];
        for (int n = 0; n < nodes; n++) {
          long[] w = new long[capacity];
          for (int i = 0; i < live; i++) {
            w[i] = random.nextLong();
          }
          Arrays.sort(w, 0, live);
          Arrays.fill(w, live, capacity, Long.MAX_VALUE);
          longNodes[n] = w;
        }
      }
      case 32 -> {
        intNodes = new int[nodes][];
        for (int n = 0; n < nodes; n++) {
          int[] w = new int[capacity];
          for (int i = 0; i < live; i++) {
            w[i] = random.nextInt();
          }
          Arrays.sort(w, 0, live);
          Arrays.fill(w, live, capacity, Integer.MAX_VALUE);
          intNodes[n] = w;
        }
      }
      case 16 -> {
        shortNodes = new short[nodes][];
        for (int n = 0; n < nodes; n++) {
          short[] w = new short[capacity];
          for (int i = 0; i < live; i++) {
            w[i] = (short) random.nextInt();
          }
          Arrays.sort(w, 0, live);
          Arrays.fill(w, live, capacity, Short.MAX_VALUE);
          shortNodes[n] = w;
        }
      }
      default -> throw new IllegalArgumentException("width " + width);
    }
  }

  @Benchmark
  public long search() {
    int i = next;
    next = (i + 1) & (order.length - 1);
    int node = order[i];
    long target = targets[i];
    return switch (width) {
      case 64 ->
          vector ? countLongs(longNodes[node], target) : scanLongs(longNodes[node], live, target);
      case 32 ->
          vector
              ? countInts(intNodes[node], (int) target)
              : scanInts(intNodes[node], live, (int) target);
      default ->
          vector
              ? countShorts(shortNodes[node], (short) target)
              : scanShorts(shortNodes[node], live, (short) target);
    };
  }

  private static long pack(int lo, int hi) {
    return ((long) lo << 32) | hi;
  }

  static long scanLongs(long[] w, int n, long t) {
    int lo = 0;
    while (lo < n && w[lo] < t) {
      lo++;
    }
    int hi = lo;
    while (hi < n && w[hi] == t) {
      hi++;
    }
    return pack(lo, hi);
  }

  static long scanInts(int[] w, int n, int t) {
    int lo = 0;
    while (lo < n && w[lo] < t) {
      lo++;
    }
    int hi = lo;
    while (hi < n && w[hi] == t) {
      hi++;
    }
    return pack(lo, hi);
  }

  static long scanShorts(short[] w, int n, short t) {
    int lo = 0;
    while (lo < n && w[lo] < t) {
      lo++;
    }
    int hi = lo;
    while (hi < n && w[hi] == t) {
      hi++;
    }
    return pack(lo, hi);
  }

  static long countLongs(long[] w, long t) {
    LongVector target = LongVector.broadcast(LONGS, t);
    LongVector below = LongVector.zero(LONGS);
    LongVector equal = LongVector.zero(LONGS);
    for (int i = 0; i < w.length; i += LONGS.length()) {
      LongVector v = LongVector.fromArray(LONGS, w, i);
      below = below.sub(v.compare(VectorOperators.LT, target).toVector());
      equal = equal.sub(v.compare(VectorOperators.EQ, target).toVector());
    }
    int lo = (int) below.reduceLanes(VectorOperators.ADD);
    return pack(lo, lo + (int) equal.reduceLanes(VectorOperators.ADD));
  }

  static long countInts(int[] w, int t) {
    IntVector target = IntVector.broadcast(INTS, t);
    IntVector below = IntVector.zero(INTS);
    IntVector equal = IntVector.zero(INTS);
    for (int i = 0; i < w.length; i += INTS.length()) {
      IntVector v = IntVector.fromArray(INTS, w, i);
      below = below.sub(v.compare(VectorOperators.LT, target).toVector());
      equal = equal.sub(v.compare(VectorOperators.EQ, target).toVector());
    }
    int lo = below.reduceLanes(VectorOperators.ADD);
    return pack(lo, lo + equal.reduceLanes(VectorOperators.ADD));
  }

  static long countShorts(short[] w, short t) {
    ShortVector target = ShortVector.broadcast(SHORTS, t);
    ShortVector below = ShortVector.zero(SHORTS);
    ShortVector equal = ShortVector.zero(SHORTS);
    for (int i = 0; i < w.length; i += SHORTS.length()) {
      ShortVector v = ShortVector.fromArray(SHORTS, w, i);
      below = below.sub(v.compare(VectorOperators.LT, target).toVector());
      equal = equal.sub(v.compare(VectorOperators.EQ, target).toVector());
    }
    int lo = below.reduceLanes(VectorOperators.ADD);
    return pack(lo, lo + equal.reduceLanes(VectorOperators.ADD));
  }
}
