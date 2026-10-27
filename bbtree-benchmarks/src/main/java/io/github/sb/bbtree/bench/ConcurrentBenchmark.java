package io.github.sb.bbtree.bench;

import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Throughput of threads that share one map of {@code size} random keys. Set the thread count
 * with JMH's {@code -t}. Each operation is a lookup with probability {@code 1 - writeRatio}, or
 * else a put of one of the keys, which replaces its value.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch"})
public class ConcurrentBenchmark {

  @Param({"bbtree", "skiplist", "rwlock-treemap"})
  public String impl;

  @Param({"long", "uuid4", "string"})
  public String keyType;

  @Param({"1000000"})
  public int size;

  @Param({"0", "0.1"})
  public double writeRatio;

  Map<Object, Object> map;
  Object[] keys;

  @Setup(Level.Trial)
  public void setUp() {
    keys = Keys.distinct(keyType, size, 5);
    map = Maps.create(impl, keyType);
    for (Object k : keys) {
      map.put(k, k);
    }
  }

  /** The random source of one thread. */
  @State(Scope.Thread)
  public static class ThreadState {
    final SplittableRandom random = new SplittableRandom(Thread.currentThread().threadId());
  }

  @Benchmark
  public Object mixed(ThreadState state) {
    Object key = keys[state.random.nextInt(size)];
    if (writeRatio > 0 && state.random.nextDouble() < writeRatio) {
      return map.put(key, key);
    }
    return map.get(key);
  }
}
