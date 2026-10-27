package io.github.sb.bbtree;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
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
 * One node search with the windows in the L1 cache: the scalar scan against the two vector
 * scans. It is in the package of the map because the scans are package-private.
 *
 * <p>The 256 nodes stay in the cache, but each search takes a random node and a random target
 * from 65,536 pairs. Thus the branch predictor cannot learn where a scan stops, as in a real
 * lookup.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-XX:+IgnoreUnrecognizedVMOptions", "-XX:+UseTransparentHugePages", "--add-modules=jdk.incubator.vector"})
public class NodeSearchBenchmark {

  private static final int NODES = 256;
  private static final int SEARCHES = 1 << 16;

  @Param({"scalar", "vector-count", "vector-scan"})
  public String scan;

  @Param({"16", "32", "64"})
  public int n;

  private WindowScan windowScan;
  private long[][] windows;
  private long[] targets;
  private int[] order;
  private int next;

  @Setup
  public void setUp() {
    windowScan =
        switch (scan) {
          case "scalar" -> WindowScan.SCALAR;
          case "vector-count" -> VectorWindowScan.COUNT;
          case "vector-scan" -> VectorWindowScan.SCAN;
          default -> throw new IllegalArgumentException(scan);
        };
    SplittableRandom random = new SplittableRandom(9);
    windows = new long[NODES][];
    targets = new long[SEARCHES];
    order = new int[SEARCHES];
    for (int i = 0; i < SEARCHES; i++) {
      targets[i] = random.nextLong();
      order[i] = random.nextInt(NODES);
    }
    for (int i = 0; i < NODES; i++) {
      long[] w = new long[n];
      for (int j = 0; j < n; j++) {
        w[j] = random.nextLong();
      }
      Arrays.sort(w);
      windows[i] = w;
    }
  }

  @Benchmark
  public long search() {
    int i = next;
    next = (i + 1) & (SEARCHES - 1);
    return windowScan.scan(windows[order[i]], 0, n, targets[i]);
  }
}
