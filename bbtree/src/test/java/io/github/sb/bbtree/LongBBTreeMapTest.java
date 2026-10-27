package io.github.sb.bbtree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LongBBTreeMapTest {

  @ParameterizedTest
  @ValueSource(ints = {4, 32})
  void matchesTreeMap(int capacity) {
    LongBBTreeMap<Long> map = new LongBBTreeMap<>(capacity, capacity);
    TreeMap<Long, Long> expected = new TreeMap<>();
    Random random = new Random(capacity);
    for (int op = 0; op < 200_000; op++) {
      long key =
          random.nextBoolean() ? random.nextInt(20_000) - 10_000 : random.nextLong() >> random.nextInt(64);
      long value = random.nextInt(1_000);
      switch (random.nextInt(6)) {
        case 0, 1, 2 -> assertEquals(expected.put(key, value), map.put(key, value));
        case 3 -> assertEquals(expected.remove(key), map.remove(key));
        case 4 -> assertEquals(expected.putIfAbsent(key, value), map.putIfAbsent(key, value));
        default -> {
          assertEquals(expected.get(key), map.get(key));
          assertEquals(expected.containsKey(key), map.containsKey(key));
        }
      }
      if (op % 20_000 == 0) {
        assertEquals(expected.size(), map.asMap().checkInvariants());
        checkGetMany(map, expected, random);
      }
    }
    assertEquals(expected.size(), map.asMap().checkInvariants());
    assertEquals(expected, new TreeMap<>(map.asMap()));
    List<Long> keys = new ArrayList<>();
    map.forEachInRange(-500, 500, (k, v) -> keys.add(k));
    assertEquals(new ArrayList<>(expected.subMap(-500L, 500L).keySet()), keys);
  }

  private static void checkGetMany(LongBBTreeMap<Long> map, TreeMap<Long, Long> expected, Random r) {
    long[] keys = new long[100];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = r.nextInt(20_000) - 10_000;
    }
    Long[] results = new Long[keys.length];
    map.getMany(keys, results);
    for (int i = 0; i < keys.length; i++) {
      assertEquals(expected.get(keys[i]), results[i]);
    }
  }

  @Test
  void signedEdges() {
    LongBBTreeMap<String> map = new LongBBTreeMap<>(4, 4);
    long[] keys = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -256, -1, 0, 1, 255, 256, Long.MAX_VALUE};
    for (long k : keys) {
      map.put(k, Long.toString(k));
    }
    for (long k : keys) {
      assertEquals(Long.toString(k), map.get(k));
    }
    for (long k : new long[] {Long.MIN_VALUE + 2, -257, -255, -2, 2, 254, 257, Long.MAX_VALUE - 1}) {
      assertEquals(null, map.get(k));
    }
    List<Long> inOrder = new ArrayList<>(map.asMap().keySet());
    for (int i = 0; i < keys.length; i++) {
      assertEquals(keys[i], inOrder.get(i));
    }
  }

  @Test
  void rejectsAMapWithOtherKeys() {
    assertThrows(
        IllegalArgumentException.class, () -> new LongBBTreeMap<>(new BBTreeMap<Long, Long>()));
  }

  @Test
  void primitiveReadersSeeSteadyKeysUnderChurn() throws Exception {
    LongBBTreeMap<Long> map = new LongBBTreeMap<>(4, 4);
    int range = 20_000;
    for (long k = 0; k < range; k += 2) {
      map.put(k, k);
    }
    int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicBoolean stop = new AtomicBoolean();
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      int role = t % 3;
      futures.add(
          pool.submit(
              () -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                long[] keys = new long[32];
                Long[] values = new Long[32];
                while (!stop.get()) {
                  if (role == 0) {
                    // Fill a run of odd keys and empty it again, so that leaves empty out.
                    long start = r.nextInt(range / 2) * 2L + 1;
                    for (long k = start; k < start + 64 && k < range; k += 2) {
                      map.put(k, k);
                    }
                    for (long k = start; k < start + 64 && k < range; k += 2) {
                      map.remove(k);
                    }
                  } else if (role == 1) {
                    long k = r.nextInt(range);
                    check(k, map.get(k));
                  } else {
                    for (int i = 0; i < keys.length; i++) {
                      keys[i] = r.nextInt(range);
                    }
                    map.getMany(keys, values);
                    for (int i = 0; i < keys.length; i++) {
                      check(keys[i], values[i]);
                    }
                  }
                }
                return null;
              }));
    }
    Thread.sleep(1_500);
    stop.set(true);
    for (Future<?> f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals(map.size(), map.asMap().checkInvariants());
  }

  private static void check(long key, Long value) {
    if (key % 2 == 0) {
      assertEquals(key, value, "a steady key is missing");
    } else if (value != null) {
      assertEquals(key, value, "a churned key has a wrong value");
    }
  }

  @Test
  void theViewSharesTheEntries() {
    LongBBTreeMap<String> map = new LongBBTreeMap<>();
    map.put(1, "a");
    Map<Long, String> view = map.asMap();
    view.put(2L, "b");
    assertEquals("b", map.get(2));
    assertEquals(2, map.size());
    assertEquals("{1=a, 2=b}", map.toString());
  }
}
