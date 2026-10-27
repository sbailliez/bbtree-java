package io.github.sb.bbtree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Maps with a vector scan must match a TreeMap and keep a valid tree. */
class VectorMapTest {

  @ParameterizedTest
  @EnumSource(VectorBBTreeMaps.Mode.class)
  void matchesTreeMap(VectorBBTreeMaps.Mode mode) {
    for (int capacity : new int[] {4, 32}) {
      BBTreeMap<Long, Long> longs = VectorBBTreeMaps.create(InlineKey.LONG, mode, capacity, capacity);
      run(longs, new TreeMap<>(), r -> (long) r.nextInt(20_000) - 10_000);
      BBTreeMap<String, Long> strings =
          VectorBBTreeMaps.create(InlineKey.STRING, mode, capacity, capacity);
      run(strings, new TreeMap<>(), r -> "https://example.com/" + r.nextInt(20_000));
      // Keys that differ only in their low 4 bytes, so that the 4-byte inner windows tie.
      BBTreeMap<Long, Long> tied = VectorBBTreeMaps.create(InlineKey.LONG, mode, capacity, capacity);
      run(tied, new TreeMap<>(), r -> (long) r.nextInt(3) << 32 | r.nextInt(20_000) * 997L);
    }
  }

  private static <K> void run(
      BBTreeMap<K, Long> map,
      TreeMap<K, Long> expected,
      java.util.function.Function<Random, K> keys) {
    Random random = new Random(5);
    for (int op = 0; op < 100_000; op++) {
      K key = keys.apply(random);
      long value = random.nextInt(1_000);
      int dice = random.nextInt(10);
      if (dice < 5) {
        assertEquals(expected.put(key, value), map.put(key, value));
      } else if (dice < 8) {
        assertEquals(expected.remove(key), map.remove(key));
      } else {
        assertEquals(expected.get(key), map.get(key));
      }
    }
    assertEquals(expected.size(), map.checkInvariants());
    Iterator<Map.Entry<K, Long>> it = map.entrySet().iterator();
    for (Map.Entry<K, Long> e : expected.entrySet()) {
      Map.Entry<K, Long> got = it.next();
      assertEquals(e.getKey(), got.getKey());
      assertEquals(e.getValue(), got.getValue());
    }
  }
}
