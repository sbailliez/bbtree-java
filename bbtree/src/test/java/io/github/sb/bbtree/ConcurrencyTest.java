package io.github.sb.bbtree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs readers and writers at the same time. Small nodes make splits and leaf removals frequent,
 * so the optimistic reads fail and start again often.
 */
class ConcurrencyTest {

  private static final int THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
  private static final long CHURN_MILLIS = 1_500;

  private ExecutorService pool;

  @BeforeEach
  void startPool() {
    pool = Executors.newFixedThreadPool(THREADS);
  }

  @AfterEach
  void stopPool() throws InterruptedException {
    pool.shutdownNow();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "a thread did not stop");
  }

  static Stream<Arguments> longMaps() {
    return Stream.of(
        Arguments.of(
            "LONG 4/4", (Supplier<BBTreeMap<Long, Long>>) () -> new BBTreeMap<>(InlineKey.LONG, 4, 4)),
        Arguments.of(
            "LONG default", (Supplier<BBTreeMap<Long, Long>>) () -> new BBTreeMap<>(InlineKey.LONG)),
        Arguments.of(
            "natural 4/4",
            (Supplier<BBTreeMap<Long, Long>>) () -> new BBTreeMap<>(null, null, 4, 4)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("longMaps")
  void disjointInsertsAllLand(String name, Supplier<BBTreeMap<Long, Long>> factory)
      throws Exception {
    BBTreeMap<Long, Long> map = factory.get();
    int perThread = 50_000;
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      int thread = t;
      tasks.add(
          () -> {
            for (int i = 0; i < perThread; i++) {
              long k = (long) i * THREADS + thread;
              // Spread the keys so that the threads write to the same leaves.
              long key = Long.reverse(k);
              map.put(key, k);
            }
            return null;
          });
    }
    runAll(tasks);
    assertEquals(THREADS * perThread, map.checkInvariants());
    for (long k = 0; k < (long) THREADS * perThread; k++) {
      assertEquals(k, map.get(Long.reverse(k)));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("longMaps")
  void readersAlwaysSeeSteadyKeys(String name, Supplier<BBTreeMap<Long, Long>> factory)
      throws Exception {
    BBTreeMap<Long, Long> map = factory.get();
    int range = 20_000;
    // The even keys stay. The writers put and remove the odd keys. A value is always its key.
    for (long k = 0; k < range; k += 2) {
      map.put(k, k);
    }
    AtomicBoolean stop = new AtomicBoolean();
    AtomicLong reads = new AtomicLong();
    List<Callable<Void>> tasks = new ArrayList<>();
    int writers = Math.max(1, THREADS / 2);
    for (int t = 0; t < writers; t++) {
      tasks.add(
          () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            while (!stop.get()) {
              long k = r.nextInt(range / 2) * 2L + 1;
              if (r.nextBoolean()) {
                map.put(k, k);
              } else {
                map.remove(k);
              }
            }
            return null;
          });
    }
    for (int t = writers; t < THREADS; t++) {
      boolean batch = t % 2 == 0;
      tasks.add(
          () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Long[] keys = new Long[32];
            Long[] values = new Long[32];
            while (!stop.get()) {
              if (batch) {
                for (int i = 0; i < keys.length; i++) {
                  keys[i] = (long) r.nextInt(range);
                }
                map.getMany(keys, values);
                for (int i = 0; i < keys.length; i++) {
                  checkRead(keys[i], values[i]);
                }
              } else {
                long k = r.nextInt(range);
                checkRead(k, map.get(k));
              }
              reads.incrementAndGet();
            }
            return null;
          });
    }
    runFor(tasks, stop);
    assertTrue(reads.get() > 0);
    map.checkInvariants();
  }

  private static void checkRead(long key, Long value) {
    if (key % 2 == 0) {
      assertEquals(key, value, "a steady key is missing");
    } else if (value != null) {
      assertEquals(key, value, "a churned key has a wrong value");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("longMaps")
  void iteratorsStayOrderedAndSeeSteadyKeys(
      String name, Supplier<BBTreeMap<Long, Long>> factory) throws Exception {
    BBTreeMap<Long, Long> map = factory.get();
    int range = 10_000;
    for (long k = 0; k < range; k += 2) {
      map.put(k, k);
    }
    AtomicBoolean stop = new AtomicBoolean();
    List<Callable<Void>> tasks = new ArrayList<>();
    int writers = Math.max(1, THREADS / 2);
    for (int t = 0; t < writers; t++) {
      tasks.add(
          () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            while (!stop.get()) {
              // Remove runs of odd keys, so that leaves empty out and leave the tree.
              long start = r.nextInt(range / 2) * 2L + 1;
              boolean put = r.nextBoolean();
              for (long k = start; k < start + 64 && k < range; k += 2) {
                if (put) {
                  map.put(k, k);
                } else {
                  map.remove(k);
                }
              }
            }
            return null;
          });
    }
    for (int t = writers; t < THREADS; t++) {
      tasks.add(
          () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            while (!stop.get()) {
              long from = r.nextInt(range);
              long expectedSteady = from % 2 == 0 ? from : from + 1;
              long previous = Long.MIN_VALUE;
              Iterator<Map.Entry<Long, Long>> it = map.iterator(from, true, null, false);
              while (it.hasNext()) {
                Map.Entry<Long, Long> e = it.next();
                long k = e.getKey();
                assertTrue(k > previous, "keys out of order: " + previous + " then " + k);
                assertEquals(k, e.getValue());
                if (k % 2 == 0) {
                  assertEquals(expectedSteady, k, "the iterator skipped a steady key");
                  expectedSteady += 2;
                }
                previous = k;
              }
              assertEquals(range, expectedSteady, "the iterator stopped early");
            }
            return null;
          });
    }
    runFor(tasks, stop);
    map.checkInvariants();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("longMaps")
  void compactionKeepsSteadyKeys(String name, Supplier<BBTreeMap<Long, Long>> factory)
      throws Exception {
    BBTreeMap<Long, Long> map = factory.get();
    int blocks = 64;
    int block = 500;
    // The first key of each block stays. The writers fill a block and empty it again, so that
    // whole subtrees empty out and the compaction removes leaves and inner nodes.
    for (long b = 0; b < blocks; b++) {
      map.put(b * block, b * block);
    }
    AtomicBoolean stop = new AtomicBoolean();
    List<Callable<Void>> tasks = new ArrayList<>();
    int writers = Math.max(2, THREADS / 2);
    for (int t = 0; t < writers; t++) {
      tasks.add(
          () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            while (!stop.get()) {
              long base = (long) r.nextInt(blocks) * block;
              for (long k = base + 1; k < base + block; k++) {
                map.put(k, k);
              }
              for (long k = base + 1; k < base + block; k++) {
                map.remove(k);
              }
            }
            return null;
          });
    }
    for (int t = writers; t < THREADS; t++) {
      boolean iterate = t % 2 == 0;
      tasks.add(
          () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            while (!stop.get()) {
              if (iterate) {
                long expected = 0;
                long previous = Long.MIN_VALUE;
                for (Map.Entry<Long, Long> e : map.entrySet()) {
                  long k = e.getKey();
                  assertTrue(k > previous, "keys out of order");
                  assertEquals(k, e.getValue());
                  if (k % block == 0) {
                    assertEquals(expected, k, "the iterator skipped a steady key");
                    expected += block;
                  }
                  previous = k;
                }
                assertEquals((long) blocks * block, expected, "the iterator stopped early");
              } else {
                long k = (long) r.nextInt(blocks) * block;
                assertEquals(k, map.get(k), "a steady key is missing");
              }
            }
            return null;
          });
    }
    runFor(tasks, stop);
    assertEquals(map.size(), map.checkInvariants());
    for (long k : new ArrayList<>(map.keySet())) {
      if (k % block != 0) {
        map.remove(k);
      }
    }
    assertEquals(blocks, map.checkInvariants());
  }

  @Test
  void mergeIsAtomic() throws Exception {
    BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 4, 4);
    int keys = 200;
    int perThread = 20_000;
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      tasks.add(
          () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < perThread; i++) {
              map.merge((long) r.nextInt(keys), 1L, Long::sum);
            }
            return null;
          });
    }
    runAll(tasks);
    long total = 0;
    for (long v : map.values()) {
      total += v;
    }
    assertEquals((long) THREADS * perThread, total);
    map.checkInvariants();
  }

  @Test
  void eachKeyIsRemovedOnce() throws Exception {
    BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 4, 4);
    int n = 50_000;
    for (long k = 0; k < n; k++) {
      map.put(k, k);
    }
    AtomicLong removed = new AtomicLong();
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      boolean ascending = t % 2 == 0;
      tasks.add(
          () -> {
            for (int i = 0; i < n; i++) {
              long k = ascending ? i : n - 1 - i;
              if (map.remove(k) != null) {
                removed.incrementAndGet();
              }
            }
            return null;
          });
    }
    runAll(tasks);
    assertEquals(n, removed.get());
    assertEquals(0, map.checkInvariants());
    assertTrue(map.isEmpty());
  }

  @Test
  void stringKeysUnderChurn() throws Exception {
    BBTreeMap<String, Integer> map = new BBTreeMap<>(InlineKey.STRING, 4, 4);
    String prefix = "https://example.com/user/";
    int range = 5_000;
    for (int i = 0; i < range; i += 2) {
      map.put(prefix + i, i);
    }
    AtomicBoolean stop = new AtomicBoolean();
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      boolean writer = t % 2 == 0;
      tasks.add(
          () -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            while (!stop.get()) {
              int i = r.nextInt(range);
              if (writer) {
                int odd = i | 1;
                if (r.nextBoolean()) {
                  map.put(prefix + odd, odd);
                } else {
                  map.remove(prefix + odd);
                }
              } else {
                Integer v = map.get(prefix + i);
                if (i % 2 == 0) {
                  assertEquals(i, v);
                } else if (v != null) {
                  assertEquals(i, v);
                }
              }
            }
            return null;
          });
    }
    runFor(tasks, stop);
    map.checkInvariants();
  }

  private void runAll(List<Callable<Void>> tasks) throws Exception {
    List<Future<Void>> futures = new ArrayList<>();
    for (Callable<Void> task : tasks) {
      futures.add(pool.submit(task));
    }
    for (Future<Void> f : futures) {
      f.get(60, TimeUnit.SECONDS);
    }
  }

  private void runFor(List<Callable<Void>> tasks, AtomicBoolean stop) throws Exception {
    List<Future<Void>> futures = new ArrayList<>();
    for (Callable<Void> task : tasks) {
      futures.add(pool.submit(task));
    }
    try {
      Thread.sleep(CHURN_MILLIS);
    } finally {
      stop.set(true);
    }
    for (Future<Void> f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
  }
}
