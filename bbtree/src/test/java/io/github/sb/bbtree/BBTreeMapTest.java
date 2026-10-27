package io.github.sb.bbtree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class BBTreeMapTest {

  @Test
  void anEmptyMapHasNothing() {
    BBTreeMap<Long, String> map = new BBTreeMap<>(InlineKey.LONG);
    assertTrue(map.isEmpty());
    assertEquals(0, map.size());
    assertNull(map.get(1L));
    assertNull(map.remove(1L));
    assertNull(map.ceilingKey(1L));
    assertThrows(NoSuchElementException.class, map::firstKey);
    assertFalse(map.entrySet().iterator().hasNext());
    assertEquals(0, map.checkInvariants());
  }

  @Test
  void putGetAndRemove() {
    BBTreeMap<Long, String> map = new BBTreeMap<>(InlineKey.LONG);
    assertNull(map.put(1L, "one"));
    assertEquals("one", map.put(1L, "uno"));
    assertEquals("uno", map.get(1L));
    assertEquals("uno", map.putIfAbsent(1L, "ein"));
    assertNull(map.putIfAbsent(2L, "two"));
    assertEquals(2, map.size());
    assertFalse(map.remove(1L, "one"));
    assertTrue(map.remove(1L, "uno"));
    assertEquals("two", map.remove(2L));
    assertTrue(map.isEmpty());
  }

  @Test
  void rejectsNull() {
    BBTreeMap<Long, String> map = new BBTreeMap<>(InlineKey.LONG);
    assertThrows(NullPointerException.class, () -> map.put(null, "x"));
    assertThrows(NullPointerException.class, () -> map.put(1L, null));
    assertThrows(NullPointerException.class, () -> map.get(null));
    assertThrows(NullPointerException.class, () -> map.remove(null));
    assertThrows(NullPointerException.class, () -> map.getMany(new Long[] {null}, new String[1]));
  }

  @Test
  void rejectsBadCapacities() {
    assertThrows(IllegalArgumentException.class, () -> new BBTreeMap<>(InlineKey.LONG, 3, 32));
    assertThrows(IllegalArgumentException.class, () -> new BBTreeMap<>(InlineKey.LONG, 32, 1025));
  }

  @Test
  void ascendingAndDescendingLoadsMakeAValidTree() {
    for (boolean ascending : new boolean[] {true, false}) {
      BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 4, 4);
      int n = 10_000;
      for (int i = 0; i < n; i++) {
        long k = ascending ? i : n - i;
        map.put(k, k * 10);
      }
      assertEquals(n, map.checkInvariants());
      assertTrue(map.height() > 4);
      List<Long> keys = new ArrayList<>(map.keySet());
      assertEquals(n, keys.size());
      for (int i = 1; i < keys.size(); i++) {
        assertTrue(keys.get(i - 1) < keys.get(i));
      }
    }
  }

  @Test
  void anAscendingLoadFillsTheLeaves() {
    BBTreeMap<Long, Long> ascending = new BBTreeMap<>(InlineKey.LONG);
    BBTreeMap<Long, Long> random = new BBTreeMap<>(InlineKey.LONG);
    int n = 100_000;
    java.util.Random r = new java.util.Random(7);
    for (long k = 0; k < n; k++) {
      ascending.put(k, k);
      random.put(r.nextLong(), k);
    }
    assertEquals(n, ascending.checkInvariants());
    // The leaves keep 31 of 32 entries: an append split moves only the last key.
    assertTrue(ascending.leafCount() <= n / 31 + 1, "leaves: " + ascending.leafCount());
    assertTrue(random.leafCount() > n / 32 * 1.2, "leaves: " + random.leafCount());
  }

  @Test
  void signedKeysSortAcrossZero() {
    BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 4, 4);
    long[] keys = {Long.MIN_VALUE, -5, -1, 0, 1, 5, Long.MAX_VALUE};
    for (long k : keys) {
      map.put(k, k);
    }
    assertArrayEquals(keys, map.keySet().stream().mapToLong(Long::longValue).toArray());
  }

  @Test
  void stringKeysWithLongSharedPrefixes() {
    BBTreeMap<String, Integer> map = new BBTreeMap<>(InlineKey.STRING, 4, 4);
    String prefix = "https://en.wikipedia.org/wiki/Category:";
    for (int i = 0; i < 2_000; i++) {
      map.put(prefix + i, i);
    }
    assertEquals(2_000, map.checkInvariants());
    for (int i = 0; i < 2_000; i++) {
      assertEquals(i, map.get(prefix + i));
    }
    assertNull(map.get(prefix));
    assertNull(map.get(prefix + "x"));
  }

  @Test
  void rangeIterationHonorsTheBounds() {
    BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 4, 4);
    LongStream.range(0, 100).forEach(i -> map.put(i * 2, i));
    assertEquals(List.of(10L, 12L, 14L), keys(map.iterator(10L, true, 14L, true)));
    assertEquals(List.of(12L), keys(map.iterator(10L, false, 14L, false)));
    assertEquals(List.of(12L, 14L), keys(map.iterator(11L, true, 15L, false)));
    assertEquals(List.of(0L, 2L), keys(map.iterator(null, true, 3L, true)));
    assertEquals(List.of(196L, 198L), keys(map.iterator(195L, true, null, true)));
    assertEquals(List.of(), keys(map.iterator(500L, true, null, true)));
    assertEquals(12L, map.ceilingKey(11L));
    assertEquals(12L, map.ceilingKey(12L));
    assertEquals(0L, map.firstKey());
  }

  @Test
  void iteratorRemoveAndEntrySetValueWriteThrough() {
    BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 4, 4);
    LongStream.range(0, 50).forEach(i -> map.put(i, i));
    Iterator<Map.Entry<Long, Long>> it = map.entrySet().iterator();
    assertThrows(IllegalStateException.class, it::remove);
    while (it.hasNext()) {
      Map.Entry<Long, Long> e = it.next();
      if (e.getKey() % 2 == 0) {
        it.remove();
      } else {
        e.setValue(-e.getKey());
      }
    }
    assertEquals(25, map.checkInvariants());
    map.forEach((k, v) -> assertEquals(-k, v));
  }

  @Test
  void getManyMatchesGet() {
    BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 8, 8);
    LongStream.range(0, 20_000).forEach(i -> map.put(i * 3, i));
    Long[] keys = new Long[1_001];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = (long) i * 41;
    }
    Long[] results = new Long[keys.length];
    map.getMany(keys, results);
    for (int i = 0; i < keys.length; i++) {
      assertEquals(map.get(keys[i]), results[i], "key " + keys[i]);
    }
    assertThrows(IllegalArgumentException.class, () -> map.getMany(keys, new Long[1]));
  }

  @Test
  void removalReclaimsEmptyNodes() {
    for (boolean ascending : new boolean[] {true, false}) {
      BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 4, 4);
      int n = 5_000;
      LongStream.range(0, n).forEach(i -> map.put(i, i));
      int leavesBefore = map.leafCount();
      LongStream.range(0, n).filter(i -> i % 100 != 0).forEach(map::remove);
      assertEquals(n / 100, map.checkInvariants());
      assertEquals(0, map.emptyLeafCount());
      assertTrue(map.leafCount() <= n / 100, "leaves before " + leavesBefore + ", after " + map.leafCount());
      for (int i = 0; i < n; i += 100) {
        map.remove(ascending ? (long) i : (long) (n - 100 - i));
      }
      assertEquals(0, map.checkInvariants());
      assertEquals(1, map.height());
      assertEquals(1, map.leafCount());
      LongStream.range(0, n).forEach(i -> map.put(i, -i));
      assertEquals(n, map.checkInvariants());
    }
  }

  @Test
  void randomRemovalOfEverythingLeavesOneLeaf() {
    BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 4, 4);
    java.util.Random r = new java.util.Random(3);
    List<Long> keys = new ArrayList<>();
    for (int i = 0; i < 20_000; i++) {
      long k = r.nextLong();
      keys.add(k);
      map.put(k, k);
    }
    java.util.Collections.shuffle(keys, r);
    keys.forEach(map::remove);
    assertEquals(0, map.checkInvariants());
    assertEquals(1, map.height());
    assertEquals(1, map.leafCount());
  }

  @Test
  void aComparatorExceptionDuringASplitLeavesNoLock() {
    // The comparator throws only when key 99 comes first. Searches compare the stored key first,
    // so only the append check of a split calls it in this order.
    java.util.Comparator<Integer> comparator =
        (a, b) -> {
          if (a == 99 && b != 99) {
            throw new IllegalStateException("boom");
          }
          return Integer.compare(a, b);
        };
    BBTreeMap<Integer, Integer> map = new BBTreeMap<>(comparator, null, 4, 4);
    for (int i = 1; i <= 4; i++) {
      map.put(i, i);
    }
    assertThrows(IllegalStateException.class, () -> map.put(99, 99));
    assertEquals(4, map.checkInvariants());
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(5),
        () -> {
          assertEquals(1, map.get(1));
          map.put(5, 5);
        });
    assertEquals(5, map.checkInvariants());
  }

  @Test
  void contractEdges() {
    BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG);
    assertThrows(NullPointerException.class, () -> map.remove(null, null));
    BBTreeMap<Object, Integer> natural = new BBTreeMap<>();
    assertThrows(ClassCastException.class, () -> natural.put(new Object(), 1));
    assertTrue(natural.isEmpty());
    BBTreeMap<byte[], Integer> bytes = new BBTreeMap<>(InlineKey.BYTES);
    bytes.put(new byte[] {1, 2}, 1);
    assertTrue(bytes.keySet().contains(new byte[] {1, 2}));
    assertTrue(bytes.keySet().remove(new byte[] {1, 2}));
    assertTrue(bytes.isEmpty());
  }

  @Test
  void getManyThrowsAsGetDoes() {
    for (int size : new int[] {1, 1_000}) {
      BBTreeMap<Object, Object> map = new BBTreeMap<>(null, null, 4, 4);
      for (long k = 0; k < size; k++) {
        map.put(k, k);
      }
      assertThrows(ClassCastException.class, () -> map.get("x"));
      assertThrows(
          ClassCastException.class, () -> map.getMany(new Object[] {0L, "x"}, new Object[2]));
    }
  }

  @Test
  void updatesOfAbsentKeysDoNotChangeTheMap() {
    BBTreeMap<Long, Long> map = new BBTreeMap<>(InlineKey.LONG, 4, 4);
    LongStream.range(0, 100).forEach(i -> map.put(i * 2, i));
    assertNull(map.remove(1L));
    assertNull(map.replace(1L, 5L));
    assertFalse(map.replace(1L, 5L, 6L));
    assertFalse(map.remove(1L, 5L));
    assertEquals(100, map.checkInvariants());
  }

  @Test
  void naturalOrderAndComparatorMaps() {
    BBTreeMap<String, Integer> natural = new BBTreeMap<>();
    BBTreeMap<String, Integer> reversed =
        new BBTreeMap<>(java.util.Comparator.<String>reverseOrder());
    for (String s : List.of("b", "a", "c")) {
      natural.put(s, 1);
      reversed.put(s, 1);
    }
    assertEquals(List.of("a", "b", "c"), new ArrayList<>(natural.keySet()));
    assertEquals(List.of("c", "b", "a"), new ArrayList<>(reversed.keySet()));
  }

  @Test
  void aComparatorExceptionReachesTheCaller() {
    BBTreeMap<Object, Integer> map = new BBTreeMap<>();
    map.put("a", 1);
    assertThrows(ClassCastException.class, () -> map.put(1, 1));
    assertThrows(ClassCastException.class, () -> map.get(1));
    assertEquals(1, map.checkInvariants());
  }

  private static List<Long> keys(Iterator<Map.Entry<Long, Long>> it) {
    List<Long> out = new ArrayList<>();
    it.forEachRemaining(e -> out.add(e.getKey()));
    return out;
  }
}
