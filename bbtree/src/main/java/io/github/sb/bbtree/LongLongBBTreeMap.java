package io.github.sb.bbtree;

import java.util.Objects;

/**
 * A {@link BBTreeMap} with primitive {@code long} keys and primitive {@code long} values. It keeps
 * the keys in signed order, as {@link Long#compare} does.
 *
 * <p>The leaves keep the values in a {@code long[]}, so a lookup reads no value object, and a
 * lookup with a primitive key reads no key object either. A scan reads no key object. The tree
 * still keeps a boxed key for each entry, as the separators of the inner nodes need it.
 *
 * <p>A map cannot keep {@code null}, so the methods that may find no entry take the value to give
 * back in that case: {@code absent}. Use {@link #containsKey} when every value is possible.
 *
 * <p>All methods are thread-safe, as the methods of {@link BBTreeMap} are. {@link #asMap()} gives
 * the same entries as a {@code BBTreeMap<Long, Long>}, which boxes each value that it gives.
 */
public final class LongLongBBTreeMap {

  /** Receives the entries of {@link #forEachInRange}. */
  @FunctionalInterface
  public interface EntryConsumer {

    /**
     * Receives one entry.
     *
     * @param key the key
     * @param value the value
     */
    void accept(long key, long value);
  }

  /** Receives the entries of {@link #forEachInRangeWhile} and {@link #forEachFrom}. */
  @FunctionalInterface
  public interface EntryPredicate {

    /**
     * Receives one entry.
     *
     * @param key the key
     * @param value the value
     * @return {@code false} to stop
     */
    boolean test(long key, long value);
  }

  private final BBTreeMap<Long, Long> map;

  /** Makes an empty map with the default node capacities. */
  public LongLongBBTreeMap() {
    this(BBTreeMap.DEFAULT_LEAF_CAPACITY, BBTreeMap.DEFAULT_INNER_CAPACITY);
  }

  /**
   * Makes an empty map with the node capacities that you give.
   *
   * @param leafCapacity the number of entries in a leaf, from 4 to 1024
   * @param innerCapacity the number of separators in an inner node, from 4 to 1024
   */
  public LongLongBBTreeMap(int leafCapacity, int innerCapacity) {
    this(
        new BBTreeMap<>(
            InlineKey.LONG,
            InlineKey.LONG,
            leafCapacity,
            innerCapacity,
            WindowScan.SCALAR,
            false,
            true));
  }

  LongLongBBTreeMap(BBTreeMap<Long, Long> map) {
    if (!map.hasLongKeys() || !map.hasPrimitiveValues()) {
      throw new IllegalArgumentException("the map does not have primitive keys and values");
    }
    this.map = map;
  }

  /**
   * Gives the value of {@code key}.
   *
   * @param key the key
   * @param absent the value to give if the map does not have the key
   * @return the value, or {@code absent}
   */
  public long get(long key, long absent) {
    return map.getLongValue(key, absent);
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
   * @param values the array that receives the value of {@code keys[i]} at index {@code i}; at
   *     least as long as {@code keys}
   * @param absent the value to write for a key that the map does not have
   */
  public void getMany(long[] keys, long[] values, long absent) {
    map.getManyLongValues(keys, values, absent);
  }

  /**
   * Sets the value of {@code key}.
   *
   * @param key the key
   * @param value the value
   * @param absent the value to give if the map did not have the key
   * @return the old value, or {@code absent}
   */
  public long put(long key, long value, long absent) {
    return map.putLongValue(key, value, absent, false);
  }

  /**
   * Sets the value of {@code key} if the map does not have the key.
   *
   * @param key the key
   * @param value the value
   * @param absent the value to give if the map did not have the key
   * @return the current value, or {@code absent} if the map did not have the key
   */
  public long putIfAbsent(long key, long value, long absent) {
    return map.putLongValue(key, value, absent, true);
  }

  /**
   * Removes {@code key}.
   *
   * @param key the key
   * @param absent the value to give if the map did not have the key
   * @return the old value, or {@code absent}
   */
  public long remove(long key, long absent) {
    Long old = map.remove(key);
    return old != null ? old : absent;
  }

  /**
   * Gives each entry with a key in {@code [from, to)} to {@code action}, in key order.
   *
   * @param from the inclusive lower bound
   * @param to the exclusive upper bound
   * @param action the action
   */
  public void forEachInRange(long from, long to, EntryConsumer action) {
    Objects.requireNonNull(action);
    map.scanLong(
        from,
        to,
        false,
        (k, unused, v) -> {
          action.accept(k, v);
          return true;
        });
  }

  /**
   * Gives each entry with a key in {@code [from, to)} to {@code action}, in key order, while the
   * action returns {@code true}.
   *
   * @param from the inclusive lower bound
   * @param to the exclusive upper bound
   * @param action the action; it returns {@code false} to stop
   */
  public void forEachInRangeWhile(long from, long to, EntryPredicate action) {
    Objects.requireNonNull(action);
    map.scanLong(from, to, false, (k, unused, v) -> action.test(k, v));
  }

  /**
   * Gives each entry with a key equal to or larger than {@code from} to {@code action}, in key
   * order, while the action returns {@code true}.
   *
   * @param from the inclusive lower bound
   * @param action the action; it returns {@code false} to stop
   */
  public void forEachFrom(long from, EntryPredicate action) {
    Objects.requireNonNull(action);
    map.scanLong(from, Long.MAX_VALUE, true, (k, unused, v) -> action.test(k, v));
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
   * Gives the same entries as a map with boxed keys and values. A change through the view changes
   * this map.
   *
   * @return the view
   */
  public BBTreeMap<Long, Long> asMap() {
    return map;
  }

  @Override
  public String toString() {
    return map.toString();
  }
}
