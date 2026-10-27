package io.github.sb.bbtree.bench;

import io.github.sb.bbtree.BBTreeMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
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
import org.openjdk.jmh.infra.Blackhole;

/** Reads the 100 entries that follow a random key, on one thread. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms8g", "-Xmx8g", "-XX:+AlwaysPreTouch"})
public class ScanBenchmark {

  private static final int LENGTH = 100;
  private static final int STARTS = 1 << 16;

  @Param({"bbtree", "treemap", "skiplist"})
  public String impl;

  @Param({"long", "uuid4", "string"})
  public String keyType;

  @Param({"1000000"})
  public int size;

  private Map<Object, Object> map;
  private Object[] starts;
  private int next;

  @Setup(Level.Trial)
  public void setUp() {
    Object[] keys = Keys.distinct(keyType, size, 4);
    map = Maps.create(impl, keyType);
    for (Object k : keys) {
      map.put(k, k);
    }
    starts = new Object[STARTS];
    System.arraycopy(keys, 0, starts, 0, STARTS);
  }

  @Benchmark
  @SuppressWarnings({"unchecked", "rawtypes"})
  public void scan(Blackhole bh) {
    Object from = starts[next];
    next = (next + 1) & (STARTS - 1);
    Iterator<? extends Map.Entry<Object, Object>> it =
        map instanceof BBTreeMap bbtree
            ? bbtree.iterator(from, true, null, false)
            : ((NavigableMap<Object, Object>) map).tailMap(from, true).entrySet().iterator();
    for (int i = 0; i < LENGTH && it.hasNext(); i++) {
      bh.consume(it.next().getValue());
    }
  }
}
