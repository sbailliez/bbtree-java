package io.github.sb.bbtree;

/**
 * Makes {@link BBTreeMap} instances whose node search uses the Java Vector API. The JVM must run
 * with {@code --add-modules jdk.incubator.vector}.
 *
 * <p>The vector count compares whole registers with no branch on the data. For key types of a
 * fixed length, such as {@link InlineKey#LONG} and {@link InlineKey#UUID}, the inner nodes keep
 * 4-byte windows, so one 128-bit NEON register compares 4 separators. The README of the project
 * gives the measured results.
 */
public final class VectorBBTreeMaps {

  /** How the vector search goes through the windows of a node. */
  public enum Mode {
    /** Compare every window and count, with no branch on the data, as the Rust bbtree does. */
    COUNT,
    /** Stop at the first register that holds a window not smaller than the target. */
    SCAN
  }

  private VectorBBTreeMaps() {}

  /**
   * Makes an empty map with the default node capacities.
   *
   * @param inlineKey the order and the encoding of the keys
   * @param mode the vector search
   * @param <K> the key type
   * @param <V> the value type
   * @return the map
   */
  public static <K, V> BBTreeMap<K, V> create(InlineKey<K> inlineKey, Mode mode) {
    return create(
        inlineKey, mode, BBTreeMap.DEFAULT_LEAF_CAPACITY, BBTreeMap.DEFAULT_INNER_CAPACITY);
  }

  /**
   * Makes an empty map with the node capacities that you give.
   *
   * @param inlineKey the order and the encoding of the keys
   * @param mode the vector search
   * @param leafCapacity the number of entries in a leaf, from 4 to 1024
   * @param innerCapacity the number of separators in an inner node, from 4 to 1024
   * @param <K> the key type
   * @param <V> the value type
   * @return the map
   */
  public static <K, V> BBTreeMap<K, V> create(
      InlineKey<K> inlineKey, Mode mode, int leafCapacity, int innerCapacity) {
    WindowScan scan = mode == Mode.COUNT ? VectorWindowScan.COUNT : VectorWindowScan.SCAN;
    return new BBTreeMap<>(
        inlineKey,
        inlineKey,
        leafCapacity,
        innerCapacity,
        scan,
        BBTreeMap.narrowSuits(inlineKey));
  }

  /**
   * Makes an empty map of primitive {@code long} keys with the default node capacities.
   *
   * @param mode the vector search
   * @param <V> the value type
   * @return the map
   */
  public static <V> LongBBTreeMap<V> createLong(Mode mode) {
    return new LongBBTreeMap<>(create(InlineKey.LONG, mode));
  }

  /**
   * Makes an empty map of primitive {@code long} keys and values with the default node capacities.
   *
   * @param mode the vector search
   * @return the map
   */
  public static LongLongBBTreeMap createLongLong(Mode mode) {
    WindowScan scan = mode == Mode.COUNT ? VectorWindowScan.COUNT : VectorWindowScan.SCAN;
    return new LongLongBBTreeMap(
        new BBTreeMap<>(
            InlineKey.LONG,
            InlineKey.LONG,
            BBTreeMap.DEFAULT_LEAF_CAPACITY,
            BBTreeMap.DEFAULT_INNER_CAPACITY,
            scan,
            BBTreeMap.narrowSuits(InlineKey.LONG),
            true));
  }

  /**
   * Gives the number of 64-bit lanes in the preferred vector register of this machine.
   *
   * @return the lane count, for example 2 on NEON and 8 on AVX-512
   */
  public static int lanes() {
    return VectorWindowScan.SPECIES.length();
  }
}
