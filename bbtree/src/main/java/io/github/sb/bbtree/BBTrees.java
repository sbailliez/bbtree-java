package io.github.sb.bbtree;

import java.util.Comparator;
import java.util.Objects;

/**
 * Makes each kind of map of this library. The kinds give different trade-offs between the key and
 * value types that they take and the speed of their lookups. The README of the project compares
 * them with measurements. The vector maps of the module bbtree-vector have their own factory,
 * {@code VectorBBTreeMaps}.
 *
 * <p>The main difference is where the keys and the values are. The Rust bbtree keeps them by
 * value in its node pages. A Java map of any key type keeps a reference to each key object and
 * each value object, so a lookup can need a cache miss for each. The maps for {@code long} keys
 * remove these objects one at a time: {@link #longKeys()} reads no key object on a lookup, {@link
 * #longToLong()} keeps no value objects, and {@link #packed()} keeps no key or value objects at
 * all, as the Rust bbtree does.
 */
public final class BBTrees {

  private BBTrees() {}

  /**
   * Makes a map of keys in their natural order, with no inline keys. It takes any {@link
   * Comparable} key, and each node search compares key objects. It is the slowest kind.
   *
   * @param <K> the key type
   * @param <V> the value type
   * @return the map
   */
  public static <K, V> BBTreeMap<K, V> naturalOrder() {
    return new BBTreeMap<>();
  }

  /**
   * Makes a map of keys in the order of {@code comparator}, with no inline keys. It takes any key
   * type, and each node search compares key objects.
   *
   * @param comparator the order of the keys
   * @param <K> the key type
   * @param <V> the value type
   * @return the map
   */
  public static <K, V> BBTreeMap<K, V> ordered(Comparator<? super K> comparator) {
    return new BBTreeMap<>(Objects.requireNonNull(comparator));
  }

  /**
   * Makes a map with inline keys: each node keeps an 8-byte window of each key, and a search
   * compares the windows. It reads a key object only when two windows tie. Use it for any key
   * type with an order-preserving encoding, such as {@code Long}, {@code UUID}, {@code String} or
   * {@code byte[]}. It is a {@link java.util.concurrent.ConcurrentMap}.
   *
   * @param inlineKey the order and the encoding of the keys, for example {@link InlineKey#UUID}
   * @param <K> the key type
   * @param <V> the value type
   * @return the map
   */
  public static <K, V> BBTreeMap<K, V> inline(InlineKey<K> inlineKey) {
    return new BBTreeMap<>(inlineKey);
  }

  /**
   * Makes a map of primitive {@code long} keys to values of any type. A lookup with a primitive
   * key reads no key object. {@link LongBBTreeMap#asMap()} gives it as a {@code Map<Long, V>}.
   *
   * @param <V> the value type
   * @return the map
   */
  public static <V> LongBBTreeMap<V> longKeys() {
    return new LongBBTreeMap<>();
  }

  /**
   * Makes a map of primitive {@code long} keys to primitive {@code long} values. The leaves keep
   * the values in a {@code long[]}, so a hit reads no value object. It still keeps a {@code Long}
   * object for each key, which gives it a {@code Map<Long, Long>} view with {@link
   * LongLongBBTreeMap#asMap()}.
   *
   * @return the map
   */
  public static LongLongBBTreeMap longToLong() {
    return new LongLongBBTreeMap();
  }

  /**
   * Makes the fastest map of primitive {@code long} keys to primitive {@code long} values. Each
   * node is a {@code long[]} that holds the keys and the values themselves, as the nodes of the
   * Rust bbtree do, so the map has no key, value or node objects. It has no {@code Map} view.
   *
   * @return the map
   */
  public static PackedLongMap packed() {
    return new PackedLongMap();
  }
}
