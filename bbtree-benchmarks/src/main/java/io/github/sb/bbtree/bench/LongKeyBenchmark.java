package io.github.sb.bbtree.bench;

import io.github.sb.bbtree.BBTreeMap;
import io.github.sb.bbtree.LongBBTreeMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Lookups of {@code long} keys on one thread, half of which miss, with the key in three forms:
 *
 * <ul>
 *   <li>{@code boxed}: the caller keeps {@link Long} objects, which are spread in memory, as
 *       keys that come from other data structures are;
 *   <li>{@code autobox}: the caller keeps {@code long} values and boxes each key for the call;
 *   <li>{@code primitive}: the caller keeps {@code long} values and calls {@link
 *       LongBBTreeMap#get(long)}. Only for bbtree.
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-XX:+IgnoreUnrecognizedVMOptions", "-XX:+UseTransparentHugePages", "-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch"})
public class LongKeyBenchmark {

  private static final int BATCH = 64;
  private static final int LOOKUPS = 1 << 20;

  @Param({"bbtree-primitive", "bbtree-autobox", "bbtree-boxed", "treemap-autobox", "treemap-boxed",
    "skiplist-autobox", "skiplist-boxed"})
  public String impl;

  @Param({"1000000"})
  public int size;

  private Map<Long, Long> map;
  private LongBBTreeMap<Long> longMap;
  private BBTreeMap<Long, Long> bbtree;
  private boolean primitive;
  private boolean boxed;
  private long[] lookups;
  private Long[] boxedLookups;
  private Long[] batchResults;
  private int next;

  @Setup(Level.Trial)
  public void setUp() {
    String[] parts = impl.split("-");
    primitive = parts[1].equals("primitive");
    boxed = parts[1].equals("boxed");
    longMap = new LongBBTreeMap<>();
    bbtree = longMap.asMap();
    map =
        switch (parts[0]) {
          case "bbtree" -> bbtree;
          case "treemap" -> new TreeMap<>();
          case "skiplist" -> new ConcurrentSkipListMap<>();
          default -> throw new IllegalArgumentException(impl);
        };
    Object[] keys = Keys.distinct("long", 2 * size, 1);
    for (int i = 0; i < size; i++) {
      map.put((Long) keys[i], (Long) keys[i]);
    }
    lookups = new long[LOOKUPS];
    Object[] chosen = new Object[LOOKUPS];
    for (int i = 0; i < LOOKUPS; i++) {
      int k = (int) ((i * 0x9E3779B97F4A7C15L >>> 33) % size);
      chosen[i] = keys[(i & 1) == 0 ? k : size + k];
    }
    Keys.shuffle(chosen, 2);
    boxedLookups = new Long[LOOKUPS];
    // The boxed keys are the objects of the key set, allocated in key-set order. The shuffle
    // makes the lookups visit them in random memory order.
    for (int i = 0; i < LOOKUPS; i++) {
      boxedLookups[i] = (Long) chosen[i];
      lookups[i] = (Long) chosen[i];
    }
    batchResults = new Long[BATCH];
    // Keep a random state, so that the JIT sees the same code shape in every fork.
    next = new SplittableRandom(3).nextInt(LOOKUPS);
  }

  @Benchmark
  public Object get() {
    int i = next;
    next = (i + 1) & (LOOKUPS - 1);
    if (primitive) {
      return longMap.get(lookups[i]);
    }
    return map.get(boxed ? boxedLookups[i] : Long.valueOf(lookups[i]));
  }

  @Benchmark
  @OperationsPerInvocation(BATCH)
  public void getBatch(Blackhole bh) {
    int from = next & (LOOKUPS - BATCH);
    next = (from + BATCH) & (LOOKUPS - 1);
    if (primitive) {
      long[] keys = new long[BATCH];
      System.arraycopy(lookups, from, keys, 0, BATCH);
      longMap.getMany(keys, batchResults);
      bh.consume(batchResults);
    } else if (map == bbtree) {
      Long[] keys = new Long[BATCH];
      for (int i = 0; i < BATCH; i++) {
        keys[i] = boxed ? boxedLookups[from + i] : Long.valueOf(lookups[from + i]);
      }
      bbtree.getMany(keys, batchResults);
      bh.consume(batchResults);
    } else {
      for (int i = 0; i < BATCH; i++) {
        bh.consume(map.get(boxed ? boxedLookups[from + i] : Long.valueOf(lookups[from + i])));
      }
    }
  }
}
