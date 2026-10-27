package io.github.sb.bbtree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A defect of the locks can make a descent wait for ever, so each test has a time limit. A spin
 * loop does not see an interrupt, so the limit runs the test in a separate thread.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PackedLongMapTest {

  private static final long ABSENT = -7;

  @ParameterizedTest
  @ValueSource(ints = {4, 5, 32})
  void matchesTreeMap(int capacity) {
    Random random = new Random(capacity);
    // Keys of every width, keys that share their first 4 or 6 bytes so that nodes get long
    // prefixes, and the two extreme keys.
    List<LongSupplier> sources =
        List.of(
            () -> random.nextInt(20_000) - 10_000L,
            () -> random.nextLong() >> random.nextInt(64),
            () -> 0x1234_5678_0000_0000L | random.nextInt(5_000),
            () -> 0x7FFF_FFFF_FFFF_0000L | random.nextInt(1 << 16),
            () ->
                random.nextBoolean()
                    ? Long.MIN_VALUE + random.nextInt(3)
                    : Long.MAX_VALUE - random.nextInt(3));
    for (LongSupplier keys : sources) {
      PackedLongMap map = new PackedLongMap(capacity, capacity);
      TreeMap<Long, Long> expected = new TreeMap<>();
      for (int op = 0; op < 60_000; op++) {
        long key = keys.getAsLong();
        long value = random.nextLong();
        switch (random.nextInt(7)) {
          case 0, 1, 2 -> assertEquals(or(expected.put(key, value)), map.put(key, value, ABSENT));
          case 3 -> assertEquals(or(expected.remove(key)), map.remove(key, ABSENT));
          case 4 ->
              assertEquals(
                  or(expected.putIfAbsent(key, value)), map.putIfAbsent(key, value, ABSENT));
          case 5 -> checkScans(map, expected, key, keys.getAsLong(), random);
          default -> {
            assertEquals(or(expected.get(key)), map.get(key, ABSENT));
            assertEquals(expected.containsKey(key), map.containsKey(key));
          }
        }
        if (op % 6_000 == 0) {
          assertEquals(expected.size(), map.checkInvariants());
          // On one thread, compaction always succeeds: the only empty leaf is the root of an
          // empty map.
          assertEquals(expected.isEmpty() ? 1 : 0, map.emptyLeafCount(), "empty leaves");
          checkGetMany(map, expected, keys);
        }
      }
      assertEquals(expected.size(), map.checkInvariants());
      checkScans(map, expected, Long.MIN_VALUE, Long.MAX_VALUE, random);
    }
  }

  private static long or(Long value) {
    return value != null ? value : ABSENT;
  }

  private static void checkGetMany(
      PackedLongMap map, TreeMap<Long, Long> expected, LongSupplier source) {
    long[] keys = new long[100];
    long[] want = new long[keys.length];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = source.getAsLong();
      want[i] = or(expected.get(keys[i]));
    }
    long[] got = new long[keys.length];
    map.getMany(keys, got, ABSENT);
    assertArrayEquals(want, got);
  }

  /** Compares the three scans with the TreeMap, between {@code a} and {@code b}. */
  private static void checkScans(
      PackedLongMap map, TreeMap<Long, Long> expected, long a, long b, Random random) {
    long from = Math.min(a, b);
    long to = Math.max(a, b);
    List<Long> got = new ArrayList<>();
    map.forEachInRangeWhile(from, to, (k, v) -> {
      got.add(k);
      got.add(v);
      return true;
    });
    assertEquals(flat(expected.subMap(from, true, to, false)), got);

    int limit = random.nextInt(70);
    List<Long> stopped = new ArrayList<>();
    map.forEachFrom(from, (k, v) -> {
      stopped.add(k);
      stopped.add(v);
      return stopped.size() < 2 * limit;
    });
    List<Long> want = flat(expected.tailMap(from, true));
    assertEquals(want.subList(0, Math.min(want.size(), Math.max(2, 2 * limit))), stopped);

    List<Long> inRange = new ArrayList<>();
    map.forEachInRangeWhile(from, to, (k, v) -> inRange.add(k) && inRange.size() < limit);
    List<Long> wantKeys = new ArrayList<>(expected.subMap(from, true, to, false).keySet());
    assertEquals(wantKeys.subList(0, Math.min(wantKeys.size(), Math.max(1, limit))), inRange);
  }

  private static List<Long> flat(NavigableMap<Long, Long> map) {
    List<Long> out = new ArrayList<>();
    for (Map.Entry<Long, Long> e : map.entrySet()) {
      out.add(e.getKey());
      out.add(e.getValue());
    }
    return out;
  }

  @Test
  void anAscendingLoadFillsTheLeaves() {
    PackedLongMap map = new PackedLongMap(32, 64);
    for (long k = 0; k < 100_000; k++) {
      map.put(k, k, ABSENT);
    }
    assertEquals(100_000, map.checkInvariants());
    // Append splits leave 31 of 32 entries: 100,000 / 31 leaves fit in 3 levels.
    assertEquals(3, map.height());
    long[] sum = {0};
    map.forEachFrom(0, (k, v) -> {
      sum[0] += v;
      return true;
    });
    assertEquals(99_999L * 100_000 / 2, sum[0]);
  }

  @Test
  void capacitiesAreChecked() {
    assertThrows(IllegalArgumentException.class, () -> new PackedLongMap(3, 32));
    assertThrows(IllegalArgumentException.class, () -> new PackedLongMap(32, 1025));
  }

  @Test
  void scansSeeSteadyKeysWhileWritersSplitLeaves() throws Exception {
    PackedLongMap map = new PackedLongMap(4, 4);
    int range = 20_000;
    // The even keys stay, with the value -key. The writers put and remove the odd keys.
    for (long k = 0; k < range; k += 2) {
      map.put(k, -k, ABSENT);
    }
    AtomicBoolean stop = new AtomicBoolean();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (int t = 0; t < 2; t++) {
        tasks.add(
            pool.submit(
                () -> {
                  ThreadLocalRandom r = ThreadLocalRandom.current();
                  while (!stop.get()) {
                    long k = r.nextInt(range / 2) * 2L + 1;
                    if (r.nextBoolean()) {
                      map.put(k, -k, ABSENT);
                    } else {
                      map.remove(k, ABSENT);
                    }
                  }
                }));
      }
      for (int t = 0; t < 2; t++) {
        tasks.add(
            pool.submit(
                () -> {
                  for (int round = 0; round < 200; round++) {
                    long[] last = {Long.MIN_VALUE};
                    int[] even = {0};
                    map.forEachFrom(
                        0,
                        (k, v) -> {
                          assertTrue(k > last[0], "order at " + k);
                          assertEquals(-k, v);
                          last[0] = k;
                          if ((k & 1) == 0) {
                            even[0]++;
                          }
                          return true;
                        });
                    assertEquals(range / 2, even[0]);
                    long[] keys = {0, 2, 4, 1_000, range - 2};
                    long[] values = new long[keys.length];
                    map.getMany(keys, values, ABSENT);
                    for (int i = 0; i < keys.length; i++) {
                      assertEquals(-keys[i], values[i]);
                    }
                  }
                  return null;
                }));
      }
      for (int i = 2; i < tasks.size(); i++) {
        tasks.get(i).get(60, TimeUnit.SECONDS);
      }
      stop.set(true);
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
    } finally {
      stop.set(true);
      pool.shutdownNow();
    }
    assertEquals(map.size(), map.checkInvariants());
  }

  @Test
  void lookupsSeeSteadyKeysWhileLeavesKeepSplitting() throws Exception {
    PackedLongMap map = new PackedLongMap(4, 4);
    int steady = 4_096;
    // The steady keys are multiples of 2^20, with the value -key. The writers insert new keys
    // between them all the time, so leaves and inner nodes keep splitting everywhere.
    for (long i = 0; i < steady; i++) {
      map.put(i << 20, -(i << 20), ABSENT);
    }
    int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
    AtomicBoolean stop = new AtomicBoolean();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> writers = new ArrayList<>();
      List<Future<?>> readers = new ArrayList<>();
      for (int t = 0; t < threads / 2; t++) {
        writers.add(
            pool.submit(
                () -> {
                  ThreadLocalRandom r = ThreadLocalRandom.current();
                  for (int n = 0; n < 400_000 && !stop.get(); n++) {
                    long k = r.nextLong((long) steady << 20) | 1;
                    map.put(k, -k, ABSENT);
                  }
                }));
      }
      for (int t = threads / 2; t < threads; t++) {
        readers.add(
            pool.submit(
                () -> {
                  ThreadLocalRandom r = ThreadLocalRandom.current();
                  long[] keys = new long[16];
                  long[] values = new long[16];
                  while (!stop.get()) {
                    long k = (long) r.nextInt(steady) << 20;
                    assertEquals(-k, map.get(k, ABSENT), "get " + k);
                    assertTrue(map.containsKey(k), "containsKey " + k);
                    for (int j = 0; j < keys.length; j++) {
                      keys[j] = (long) r.nextInt(steady) << 20;
                    }
                    map.getMany(keys, values, ABSENT);
                    for (int j = 0; j < keys.length; j++) {
                      assertEquals(-keys[j], values[j], "getMany " + keys[j]);
                    }
                  }
                  return null;
                }));
      }
      for (Future<?> w : writers) {
        w.get(120, TimeUnit.SECONDS);
      }
      stop.set(true);
      for (Future<?> reader : readers) {
        reader.get(10, TimeUnit.SECONDS);
      }
    } finally {
      stop.set(true);
      pool.shutdownNow();
    }
    assertEquals(map.size(), map.checkInvariants());
  }

  @Test
  void removingEveryKeyLeavesOneEmptyLeaf() {
    PackedLongMap map = new PackedLongMap(4, 4);
    List<Long> keys = new ArrayList<>();
    for (long k = 0; k < 50_000; k++) {
      keys.add(k * 7919 % 50_000);
    }
    for (long k : keys) {
      map.put(k, k, ABSENT);
    }
    assertTrue(map.height() >= 6);
    java.util.Collections.shuffle(keys, new Random(3));
    for (int i = 0; i < keys.size(); i++) {
      assertEquals(keys.get(i), map.remove(keys.get(i), ABSENT));
      if (i % 5_000 == 0) {
        assertEquals(keys.size() - i - 1, map.checkInvariants());
        assertEquals(0, map.emptyLeafCount());
      }
    }
    assertEquals(0, map.checkInvariants());
    assertEquals(1, map.height());
    assertEquals(1, map.emptyLeafCount());
    assertTrue(map.isEmpty());
    map.put(5, 6, ABSENT);
    assertEquals(6, map.get(5, ABSENT));
  }

  @Test
  void lookupsSeeSteadyKeysWhileEmptyLeavesAreRemoved() throws Exception {
    PackedLongMap map = new PackedLongMap(4, 4);
    int steady = 1_024;
    // The steady keys are multiples of 2^20. The writers insert keys between them and remove
    // them again, so leaves that hold only such keys empty out and leave the tree.
    for (long i = 0; i < steady; i++) {
      map.put(i << 20, -(i << 20), ABSENT);
    }
    int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
    AtomicBoolean stop = new AtomicBoolean();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> writers = new ArrayList<>();
      List<Future<?>> readers = new ArrayList<>();
      for (int t = 0; t < threads / 2; t++) {
        // Each writer has its own keys in a gap, so that it removes only keys that it put.
        long offset = 1 + 64L * t;
        writers.add(
            pool.submit(
                () -> {
                  ThreadLocalRandom r = ThreadLocalRandom.current();
                  long[] mine = new long[64];
                  for (int n = 0; n < 200_000 && !stop.get(); n++) {
                    // A run of keys in one gap, then their removal, so that whole leaves empty.
                    long base = (long) r.nextInt(steady) << 20;
                    for (int j = 0; j < mine.length; j++) {
                      mine[j] = base + offset + j;
                      map.put(mine[j], -mine[j], ABSENT);
                    }
                    for (long k : mine) {
                      assertEquals(-k, map.remove(k, ABSENT));
                    }
                    n += mine.length;
                  }
                }));
      }
      for (int t = threads / 2; t < threads; t++) {
        readers.add(
            pool.submit(
                () -> {
                  ThreadLocalRandom r = ThreadLocalRandom.current();
                  long[] keys = new long[16];
                  long[] values = new long[16];
                  while (!stop.get()) {
                    long k = (long) r.nextInt(steady) << 20;
                    assertEquals(-k, map.get(k, ABSENT), "get " + k);
                    for (int j = 0; j < keys.length; j++) {
                      keys[j] = (long) r.nextInt(steady) << 20;
                    }
                    map.getMany(keys, values, ABSENT);
                    for (int j = 0; j < keys.length; j++) {
                      assertEquals(-keys[j], values[j], "getMany " + keys[j]);
                    }
                    int[] seen = {0};
                    long[] last = {Long.MIN_VALUE};
                    map.forEachFrom(k, (key, v) -> {
                      assertTrue(key > last[0], "order");
                      last[0] = key;
                      if ((key & ((1L << 20) - 1)) == 0) {
                        seen[0]++;
                      }
                      return seen[0] < 8;
                    });
                    assertEquals(Math.min(8, steady - (int) (k >> 20)), seen[0], "scan from " + k);
                  }
                  return null;
                }));
      }
      for (Future<?> w : writers) {
        w.get(120, TimeUnit.SECONDS);
      }
      stop.set(true);
      for (Future<?> reader : readers) {
        reader.get(10, TimeUnit.SECONDS);
      }
    } finally {
      stop.set(true);
      pool.shutdownNow();
    }
    assertEquals(steady, map.checkInvariants());
  }
}
