package io.github.sb.bbtree;

import java.util.Objects;

/**
 * A {@link BBTreeMap} with primitive {@code long} keys. It keeps the keys in signed order, as
 * {@link Long#compare} does.
 *
 * <p>A lookup with a primitive key reads no key object. A lookup with a boxed key must first read
 * the {@link Long}, and when the caller keeps its keys as boxed objects, that read is often a
 * cache miss of its own. The tree still keeps a boxed key for each entry, for iteration and for
 * the {@link java.util.Map} view.
 *
 * <p>All methods are thread-safe, as the methods of {@link BBTreeMap} are. {@link #asMap()} gives
 * the same entries as a {@code BBTreeMap<Long, V>}.
 *
 * @param <V> the value type
 */
public final class LongBBTreeMap<V> {

  /** Receives the entries of {@link #forEachInRange}. */
  @FunctionalInterface
  public interface EntryConsumer<V> {

    /**
     * Receives one entry.
     *
     * @param key the key
     * @param value the value
     */
    void accept(long key, V value);
  }

  private final BBTreeMap<Long, V> map;

  /** Makes an empty map with the default node capacities. */
  public LongBBTreeMap() {
    this(BBTreeMap.DEFAULT_LEAF_CAPACITY, BBTreeMap.DEFAULT_INNER_CAPACITY);
  }

  /**
   * Makes an empty map with the node capacities that you give.
   *
   * @param leafCapacity the number of entries in a leaf, from 4 to 1024
   * @param innerCapacity the number of separators in an inner node, from 4 to 1024
   */
  public LongBBTreeMap(int leafCapacity, int innerCapacity) {
    this(new BBTreeMap<>(InlineKey.LONG, leafCapacity, innerCapacity));
  }

  LongBBTreeMap(BBTreeMap<Long, V> map) {
    if (!map.hasLongKeys()) {
      throw new IllegalArgumentException("the map does not use InlineKey.LONG");
    }
    this.map = map;
  }

  /**
   * Gives the value of {@code key}.
   *
   * @param key the key
   * @return the value, or {@code null} if the map does not have the key
   */
  public V get(long key) {
    return map.getLong(key);
  }

  /**
   * Gives true if the map has {@code key}.
   *
   * @param key the key
   * @return true if the map has the key
   */
  public boolean containsKey(long key) {
    return map.getLong(key) != null;
  }

  /**
   * Looks up many keys at the same time, as {@link BBTreeMap#getMany} does.
   *
   * @param keys the keys to look up
   * @param results the array that receives the value of {@code keys[i]} at index {@code i}, or
   *     {@code null} if the map does not have the key; at least as long as {@code keys}
   */
  public void getMany(long[] keys, V[] results) {
    map.getManyLong(keys, results);
  }

  /**
   * Sets the value of {@code key}.
   *
   * @param key the key
   * @param value the value, not {@code null}
   * @return the old value, or {@code null} if the map did not have the key
   */
  public V put(long key, V value) {
    return map.put(key, value);
  }

  /**
   * Sets the value of {@code key} if the map does not have the key.
   *
   * @param key the key
   * @param value the value, not {@code null}
   * @return the current value, or {@code null} if the map did not have the key
   */
  public V putIfAbsent(long key, V value) {
    return map.putIfAbsent(key, value);
  }

  /**
   * Removes {@code key}.
   *
   * @param key the key
   * @return the old value, or {@code null} if the map did not have the key
   */
  public V remove(long key) {
    return map.remove(key);
  }

  /**
   * Gives each entry with a key in {@code [from, to)} to {@code action}, in key order.
   *
   * @param from the inclusive lower bound
   * @param to the exclusive upper bound
   * @param action the action
   */
  public void forEachInRange(long from, long to, EntryConsumer<? super V> action) {
    Objects.requireNonNull(action);
    map.forEachInRange(from, to, (k, v) -> action.accept(k, v));
  }

  /**
   * Gives the number of entries. It is exact when no write runs at the same time.
   *
   * @return the number of entries
   */
  public int size() {
    return map.size();
  }

  /**
   * Gives true if the map has no entries.
   *
   * @return true if the map is empty
   */
  public boolean isEmpty() {
    return map.isEmpty();
  }

  /**
   * Gives the same entries as a map with boxed keys. A change through the view changes this map.
   *
   * @return the view
   */
  public BBTreeMap<Long, V> asMap() {
    return map;
  }

  @Override
  public String toString() {
    return map.toString();
  }
}
