package io.github.sb.bbtree.bench;

import io.github.sb.bbtree.BBTreeMap;
import io.github.sb.bbtree.InlineKey;
import io.github.sb.bbtree.LongBBTreeMap;
import io.github.sb.bbtree.VectorBBTreeMaps;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;

/**
 * The workloads of {@code bench/main.go} in <a
 * href="https://github.com/richardartoul/bbtree-go">bbtree-go</a>, which runs the same workloads on
 * the Go port and on the Rust bbtree ({@code bench/reference.rs}). It takes the same flags and
 * prints the same CSV row, with {@code java} as the language, so the three can run side by side.
 *
 * <p>The keys, the queries, the values and the checksums are the same as in Go and Rust:
 *
 * <ul>
 *   <li>{@code uint64} keys use {@link LongBBTreeMap}. A key is stored as {@code key ^
 *       Long.MIN_VALUE}, so that the signed order of Java is the unsigned order of Go and Rust,
 *       and {@code scan64} gives the same entries.
 *   <li>{@code string} keys use {@link BBTreeMap} with {@link InlineKey#STRING}.
 * </ul>
 *
 * <p>Java has one map type, which is concurrent. The {@code single} mode runs it on one thread.
 * The values are {@link Long} objects. A JVM needs warm-up, so the program repeats the whole
 * workload {@code -repeat} times with a new map each time, and prints one row for each
 * repetition. Compare the last rows with Go and Rust. The flag {@code -impl} chooses the map:
 * {@code vector} (the default) for the vector count of bbtree-vector, or {@code scalar}.
 */
public final class CrossLanguage {

  private CrossLanguage() {}

  static long mix(long x) {
    x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
    x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
    return x ^ (x >>> 31);
  }

  record Options(
      int n,
      int queries,
      int threads,
      String kind,
      String mode,
      String op,
      int repeat,
      boolean vector) {}

  public static void main(String[] args) throws Exception {
    Options o =
        new Options(
            Integer.parseInt(flag(args, "-n", "1048576")),
            Integer.parseInt(flag(args, "-queries", "1048576")),
            Integer.parseInt(flag(args, "-threads", "1")),
            flag(args, "-kind", "uint64"),
            flag(args, "-mode", "single"),
            flag(args, "-op", "hit"),
            Integer.parseInt(flag(args, "-repeat", "5")),
            flag(args, "-impl", "vector").equals("vector"));
    if (o.n < 64
        || o.queries < 64
        || o.queries % 64 != 0
        || o.threads < 1
        || !(o.mode.equals("single") || o.mode.equals("concurrent"))
        || (o.threads != 1 && !o.mode.equals("concurrent"))) {
      throw new IllegalArgumentException("invalid benchmark options");
    }
    Workload<?> workload =
        switch (o.kind) {
          case "uint64" -> new LongWorkload(o);
          case "string" -> new StringWorkload(o);
          default -> throw new IllegalArgumentException("unknown key kind");
        };
    for (int r = 0; r < o.repeat; r++) {
      workload.runOnce();
    }
  }

  private static String flag(String[] args, String name, String fallback) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (args[i].equals(name)) {
        return args[i + 1];
      }
    }
    return fallback;
  }

  /** One workload. The subclasses give the map operations for one key type. */
  abstract static class Workload<M> {
    final Options o;

    Workload(Options o) {
      this.o = o;
    }

    abstract M newMap();

    /** Puts key {@code i} of {@code keys}, or of the queries, and gives the old value or 0. */
    abstract long put(M map, boolean query, int i, long value);

    abstract long get(M map, int query);

    abstract long remove(M map, int key);

    /** Looks up queries {@code [from, to)} in one batch and gives the sum of the values. */
    abstract long getMany(M map, int from, int to, Object buffers);

    abstract Object newBuffers();

    /** Gives the sum of the values of the first 64 entries from query {@code i} on. */
    abstract long scan64(M map, int query);

    void runOnce() throws Exception {
      M map = newMap();
      if (!o.op.equals("insert")) {
        for (int i = 0; i < o.n; i++) {
          put(map, false, i, i * 3L + 1);
        }
      }
      System.gc();
      int ops = o.op.equals("insert") || o.op.equals("delete") ? o.n : o.queries;
      long[] sums = new long[o.threads];
      Thread[] workers = new Thread[o.threads];
      CyclicBarrier ready = new CyclicBarrier(o.threads + 1);
      CyclicBarrier done = new CyclicBarrier(o.threads + 1);
      for (int w = 0; w < o.threads; w++) {
        int worker = w;
        workers[w] =
            new Thread(
                () -> {
                  try {
                    ready.await();
                    int begin = (int) ((long) ops * worker / o.threads);
                    int end = (int) ((long) ops * (worker + 1) / o.threads);
                    sums[worker] = work(map, begin, end);
                    done.await();
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
        workers[w].start();
      }
      ready.await();
      long start = System.nanoTime();
      done.await();
      long elapsed = System.nanoTime() - start;
      for (Thread t : workers) {
        t.join();
      }
      long checksum = 0;
      for (long s : sums) {
        checksum += s;
      }
      System.out.printf(
          "%s,%s,%s,%s,%d,%d,%d,%.2f,%s,%d%n",
          o.vector ? "java-vector" : "java",
          o.kind,
          o.mode,
          o.op,
          o.threads,
          o.n,
          ops,
          (double) elapsed / ops,
          Long.toUnsignedString(checksum),
          0);
    }

    private long work(M map, int begin, int end) {
      long sum = 0;
      switch (o.op) {
        case "insert" -> {
          for (int i = begin; i < end; i++) {
            sum += put(map, false, i, i * 3L + 1) == 0 ? 1 : 0;
          }
        }
        case "hit", "miss" -> {
          for (int i = begin; i < end; i++) {
            sum += get(map, i);
          }
        }
        case "batch" -> {
          Object buffers = newBuffers();
          for (int i = begin; i < end; i += 64) {
            sum += getMany(map, i, Math.min(i + 64, end), buffers);
          }
        }
        case "update" -> {
          for (int i = begin; i < end; i++) {
            sum += put(map, true, i, i * 5L + 2);
          }
        }
        case "mixed" -> {
          for (int i = begin; i < end; i++) {
            if (i % 10 == 0) {
              long index = Long.remainderUnsigned(mix(i + 0x12345678L), o.n);
              put(map, true, i, index * 3 + 1);
            } else {
              sum += get(map, i);
            }
          }
        }
        case "delete" -> {
          for (int i = begin; i < end; i++) {
            sum += remove(map, i);
          }
        }
        case "scan64" -> {
          for (int i = begin; i < end; i++) {
            sum += scan64(map, i);
          }
        }
        default -> throw new IllegalArgumentException("unknown operation " + o.op);
      }
      return sum;
    }

    /** Gives the key number of query {@code i}, as Go and Rust compute it. */
    long queryKey(int i) {
      long index = Long.remainderUnsigned(mix(i + 0x12345678L), o.n);
      return mix(2 * index + (o.op.equals("miss") ? 1 : 0));
    }
  }

  static final class LongWorkload extends Workload<LongBBTreeMap<Long>> {
    private final long[] keys;
    private final long[] queries;

    LongWorkload(Options o) {
      super(o);
      keys = new long[o.n];
      for (int i = 0; i < o.n; i++) {
        keys[i] = mix(2L * i) ^ Long.MIN_VALUE;
      }
      queries = new long[o.queries];
      for (int i = 0; i < o.queries; i++) {
        queries[i] = queryKey(i) ^ Long.MIN_VALUE;
      }
    }

    @Override
    LongBBTreeMap<Long> newMap() {
      return o.vector
          ? VectorBBTreeMaps.createLong(VectorBBTreeMaps.Mode.COUNT)
          : new LongBBTreeMap<>();
    }

    @Override
    long put(LongBBTreeMap<Long> map, boolean query, int i, long value) {
      Long old = map.put(query ? queries[i] : keys[i], value);
      return old == null ? 0 : old;
    }

    @Override
    long get(LongBBTreeMap<Long> map, int query) {
      Long v = map.get(queries[query]);
      return v == null ? 0 : v;
    }

    @Override
    long remove(LongBBTreeMap<Long> map, int key) {
      Long v = map.remove(keys[key]);
      return v == null ? 0 : v;
    }

    @Override
    Object newBuffers() {
      return new Object[] {new long[64], new Long[64]};
    }

    @Override
    long getMany(LongBBTreeMap<Long> map, int from, int to, Object buffers) {
      Object[] b = (Object[]) buffers;
      long[] batch = (long[]) b[0];
      Long[] values = (Long[]) b[1];
      int n = to - from;
      if (n != batch.length) {
        batch = Arrays.copyOfRange(queries, from, to);
      } else {
        System.arraycopy(queries, from, batch, 0, n);
      }
      map.getMany(batch, values);
      long sum = 0;
      for (int i = 0; i < n; i++) {
        Long v = values[i];
        sum += v == null ? 0 : v;
      }
      return sum;
    }

    @Override
    long scan64(LongBBTreeMap<Long> map, int query) {
      return firstValues(map.asMap(), queries[query]);
    }
  }

  static final class StringWorkload extends Workload<BBTreeMap<String, Long>> {
    private final String[] keys;
    private final String[] queries;

    StringWorkload(Options o) {
      super(o);
      keys = new String[o.n];
      for (int i = 0; i < o.n; i++) {
        keys[i] = key(mix(2L * i));
      }
      queries = new String[o.queries];
      for (int i = 0; i < o.queries; i++) {
        // A new String object for each query, as in Go: not the stored key object.
        queries[i] = key(queryKey(i));
      }
    }

    private static String key(long x) {
      String hex = Long.toHexString(x);
      return "tenant/region/service/" + "0".repeat(16 - hex.length()) + hex;
    }

    @Override
    BBTreeMap<String, Long> newMap() {
      return o.vector
          ? VectorBBTreeMaps.create(InlineKey.STRING, VectorBBTreeMaps.Mode.COUNT)
          : new BBTreeMap<>(InlineKey.STRING);
    }

    @Override
    long put(BBTreeMap<String, Long> map, boolean query, int i, long value) {
      Long old = map.put(query ? queries[i] : keys[i], value);
      return old == null ? 0 : old;
    }

    @Override
    long get(BBTreeMap<String, Long> map, int query) {
      Long v = map.get(queries[query]);
      return v == null ? 0 : v;
    }

    @Override
    long remove(BBTreeMap<String, Long> map, int key) {
      Long v = map.remove(keys[key]);
      return v == null ? 0 : v;
    }

    @Override
    Object newBuffers() {
      return new Object[] {new String[64], new Long[64]};
    }

    @Override
    long getMany(BBTreeMap<String, Long> map, int from, int to, Object buffers) {
      Object[] b = (Object[]) buffers;
      String[] batch = (String[]) b[0];
      Long[] values = (Long[]) b[1];
      int n = to - from;
      if (n != batch.length) {
        batch = Arrays.copyOfRange(queries, from, to);
      } else {
        System.arraycopy(queries, from, batch, 0, n);
      }
      map.getMany(batch, values);
      long sum = 0;
      for (int i = 0; i < n; i++) {
        Long v = values[i];
        sum += v == null ? 0 : v;
      }
      return sum;
    }

    @Override
    long scan64(BBTreeMap<String, Long> map, int query) {
      return firstValues(map, queries[query]);
    }
  }

  private static <K> long firstValues(BBTreeMap<K, Long> map, K from) {
    long sum = 0;
    Iterator<Map.Entry<K, Long>> it = map.iterator(from, true, null, false);
    for (int count = 0; count < 64 && it.hasNext(); count++) {
      sum += it.next().getValue();
    }
    return sum;
  }
}
