package io.github.sb.bbtree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs random operations on a {@link BBTreeMap} and a {@link TreeMap} side by side, compares
 * every answer, and checks the tree structure as it goes.
 */
class DifferentialTest {

  private static final int OPERATIONS = 60_000;
  private static final int CHECK_EVERY = 2_000;

  record Config<K>(
      String name,
      Supplier<BBTreeMap<K, Integer>> map,
      Comparator<? super K> order,
      Function<Random, K> key) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Arguments> configs() {
    Function<Random, Long> smallLong = r -> (long) r.nextInt(6_000) - 3_000;
    Function<Random, Long> wideLong =
        r -> r.nextBoolean() ? r.nextLong() >> r.nextInt(64) : (long) r.nextInt(4_000);
    Function<Random, String> url =
        r -> "https://example.com/catalog/item/" + (r.nextInt(5_000) * 7919 % 100_000);
    Function<Random, String> shortString =
        r -> Integer.toString(r.nextInt(5_000), 36) + (r.nextBoolean() ? "" : "\0");
    Function<Random, byte[]> bytes =
        r -> ("k" + r.nextInt(5_000)).getBytes(StandardCharsets.UTF_8);
    Function<Random, UUID> uuid = r -> new UUID(r.nextInt(3), r.nextInt(5_000));
    Function<Random, Integer> integer = r -> r.nextInt(5_000) * (r.nextBoolean() ? 1 : -1);
    Comparator<Long> longs = Comparator.naturalOrder();
    Comparator<String> strings = Comparator.naturalOrder();
    return Stream.of(
        config("LONG 4/4", () -> new BBTreeMap<>(InlineKey.LONG, 4, 4), longs, smallLong),
        config("LONG default", () -> new BBTreeMap<>(InlineKey.LONG), longs, wideLong),
        config("LONG 7/5", () -> new BBTreeMap<>(InlineKey.LONG, 7, 5), longs, wideLong),
        config("natural Long 4/4", () -> new BBTreeMap<>(longs, null, 4, 4), longs, smallLong),
        config("natural Long default", BBTreeMap::new, longs, wideLong),
        config("STRING 4/4", () -> new BBTreeMap<>(InlineKey.STRING, 4, 4), strings, url),
        config("STRING default", () -> new BBTreeMap<>(InlineKey.STRING), strings, shortString),
        config(
            "BYTES 5/4",
            () -> new BBTreeMap<>(InlineKey.BYTES, 5, 4),
            InlineKey.BYTES,
            bytes),
        config(
            "UUID 4/4",
            () -> new BBTreeMap<>(InlineKey.UUID, 4, 4),
            Comparator.<UUID>naturalOrder(),
            uuid),
        config(
            "INTEGER 6/4",
            () -> new BBTreeMap<>(InlineKey.INTEGER, 6, 4),
            Comparator.<Integer>naturalOrder(),
            integer));
  }

  private static <K> Arguments config(
      String name,
      Supplier<BBTreeMap<K, Integer>> map,
      Comparator<? super K> order,
      Function<Random, K> key) {
    return Arguments.of(new Config<>(name, map, order, key));
  }

  @ParameterizedTest
  @MethodSource("configs")
  <K> void matchesTreeMap(Config<K> config) {
    for (long seed = 1; seed <= 3; seed++) {
      run(config, seed);
    }
  }

  private static <K> void run(Config<K> config, long seed) {
    Random random = new Random(seed);
    BBTreeMap<K, Integer> map = config.map().get();
    TreeMap<K, Integer> expected = new TreeMap<>(config.order());
    String where = config + " seed " + seed;
    for (int op = 0; op < OPERATIONS; op++) {
      // Grow for the first half, then shrink, so that removals empty leaves.
      boolean growing = (op / (OPERATIONS / 4)) % 2 == 0;
      int insertWeight = growing ? 60 : 25;
      K key = config.key().apply(random);
      int value = random.nextInt(1_000);
      int dice = random.nextInt(100);
      String at = where + " op " + op;
      if (dice < insertWeight) {
        assertEquals(expected.put(key, value), map.put(key, value), at);
      } else if (dice < insertWeight + 5) {
        assertEquals(expected.putIfAbsent(key, value), map.putIfAbsent(key, value), at);
      } else if (dice < 90) {
        assertEquals(expected.remove(key), map.remove(key), at);
      } else if (dice < 92) {
        Integer current = expected.get(key);
        int guess = current != null && random.nextBoolean() ? current : value;
        assertEquals(expected.remove(key, guess), map.remove(key, guess), at);
      } else if (dice < 94) {
        assertEquals(expected.replace(key, value), map.replace(key, value), at);
      } else if (dice < 96) {
        Integer current = expected.get(key);
        int guess = current != null && random.nextBoolean() ? current : value;
        assertEquals(
            expected.replace(key, guess, value), map.replace(key, guess, value), at);
      } else if (dice < 98) {
        assertEquals(expected.get(key), map.get(key), at);
        assertEquals(show(expected.ceilingKey(key)), show(map.ceilingKey(key)), at);
      } else {
        checkRange(map, expected, config.key().apply(random), key, random, at);
      }
      if (op % CHECK_EVERY == 0) {
        checkAll(map, expected, at);
      }
    }
    checkAll(map, expected, where + " at the end");
    map.clear();
    expected.clear();
    checkAll(map, expected, where + " after clear");
  }

  private static <K> void checkRange(
      BBTreeMap<K, Integer> map,
      TreeMap<K, Integer> expected,
      K a,
      K b,
      Random random,
      String at) {
    K from = expected.comparator().compare(a, b) <= 0 ? a : b;
    K to = from == a ? b : a;
    boolean fromInclusive = random.nextBoolean();
    boolean toInclusive = random.nextBoolean();
    if (expected.comparator().compare(from, to) == 0 && !(fromInclusive && toInclusive)) {
      fromInclusive = toInclusive = true;
    }
    NavigableMap<K, Integer> sub = expected.subMap(from, fromInclusive, to, toInclusive);
    assertEntries(sub, map.iterator(from, fromInclusive, to, toInclusive), at + " range");
    List<K> keys = new ArrayList<>();
    map.forEachInRange(from, to, (k, v) -> keys.add(k));
    assertEquals(new ArrayList<>(expected.subMap(from, to).keySet()), keys, at + " forEach");
  }

  private static <K> void checkAll(
      BBTreeMap<K, Integer> map, TreeMap<K, Integer> expected, String at) {
    assertEquals(expected.size(), map.checkInvariants(), at);
    assertEquals(expected.size(), map.size(), at);
    assertEquals(expected.isEmpty(), map.isEmpty(), at);
    assertEntries(expected, map.entrySet().iterator(), at);
    for (Map.Entry<K, Integer> e : expected.entrySet()) {
      assertEquals(e.getValue(), map.get(e.getKey()), at);
    }
    if (!expected.isEmpty()) {
      assertEquals(expected.firstKey(), map.firstKey(), at);
    }
  }

  private static <K> void assertEntries(
      Map<K, Integer> expected, Iterator<Map.Entry<K, Integer>> actual, String at) {
    Iterator<Map.Entry<K, Integer>> e = expected.entrySet().iterator();
    int i = 0;
    while (e.hasNext()) {
      Map.Entry<K, Integer> want = e.next();
      if (!actual.hasNext()) {
        throw new AssertionError(at + ": iterator ended at " + i + " of " + expected.size());
      }
      Map.Entry<K, Integer> got = actual.next();
      assertEquals(show(want.getKey()), show(got.getKey()), at + " key " + i);
      assertEquals(want.getValue(), got.getValue(), at + " value " + i);
      i++;
    }
    if (actual.hasNext()) {
      throw new AssertionError(at + ": iterator has more than " + i + " entries");
    }
  }

  private static Object show(Object key) {
    return key instanceof byte[] b ? Arrays.toString(b) : key;
  }
}
