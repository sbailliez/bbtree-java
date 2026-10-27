package io.github.sb.bbtree.bench;

import io.github.sb.bbtree.BBTreeMap;
import java.util.Map;
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
 * Lookups on one thread, half of which miss, in a map of {@code size} random keys. The batch
 * benchmark gives {@link BBTreeMap#getMany} 64 keys at a time, and calls {@code get} 64 times on
 * the other maps.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch", "--add-modules=jdk.incubator.vector"})
public class GetBenchmark {

  private static final int BATCH = 64;
  private static final int LOOKUPS = 1 << 20;

  @Param({"bbtree", "bbtree-vector-scan", "bbtree-vector-count", "bbtree-noinline", "treemap", "skiplist"})
  public String impl;

  @Param({"long", "uuid4", "uuid7", "string"})
  public String keyType;

  @Param({"1000000"})
  public int size;

  private Map<Object, Object> map;
  private Object[] lookups;
  private Object[] batchResults;
  private int next;

  @Setup(Level.Trial)
  public void setUp() {
    Object[] keys = Keys.distinct(keyType, 2 * size, 1);
    map = Maps.create(impl, keyType);
    for (int i = 0; i < size; i++) {
      map.put(keys[i], keys[i]);
    }
    // Half of the lookups hit the first half of the keys, half miss with the second half.
    lookups = new Object[LOOKUPS];
    for (int i = 0; i < LOOKUPS; i++) {
      int k = (int) ((i * 0x9E3779B97F4A7C15L >>> 33) % size);
      lookups[i] = keys[(i & 1) == 0 ? k : size + k];
    }
    Keys.shuffle(lookups, 2);
    batchResults = new Object[BATCH];
  }

  @Benchmark
  public Object get() {
    Object key = lookups[next];
    next = (next + 1) & (LOOKUPS - 1);
    return map.get(key);
  }

  @Benchmark
  @OperationsPerInvocation(BATCH)
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void getBatch(Blackhole bh) {
    int from = next;
    next = (next + BATCH) & (LOOKUPS - 1);
    if (map instanceof BBTreeMap bbtree) {
      Object[] keys = new Object[BATCH];
      System.arraycopy(lookups, from, keys, 0, BATCH);
      bbtree.getMany(keys, batchResults);
      bh.consume(batchResults);
    } else {
      for (int i = 0; i < BATCH; i++) {
        bh.consume(map.get(lookups[from + i]));
      }
    }
  }
}
