package io.github.sb.bbtree.bench;

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

/**
 * Builds a map of {@code size} random keys from empty, on one thread. The score is the time of
 * one insert, averaged over the build.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(value = 1, jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch"})
public class PutBenchmark {

  public static final int SIZE = 1_000_000;

  @Param({"bbtree", "bbtree-noinline", "treemap", "skiplist"})
  public String impl;

  @Param({"long", "uuid4", "uuid7", "string"})
  public String keyType;

  private Object[] keys;
  private Map<Object, Object> map;

  @Setup(Level.Trial)
  public void generate() {
    keys = Keys.distinct(keyType, SIZE, 3);
  }

  @Setup(Level.Iteration)
  public void empty() {
    map = Maps.create(impl, keyType);
    System.gc();
  }

  @Benchmark
  @OperationsPerInvocation(SIZE)
  public Map<Object, Object> build() {
    for (Object k : keys) {
      map.put(k, k);
    }
    return map;
  }
}
