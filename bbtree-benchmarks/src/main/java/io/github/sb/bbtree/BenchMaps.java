package io.github.sb.bbtree;

/** Makes maps with a chosen width of the inner windows, for the benchmarks. */
public final class BenchMaps {

  private BenchMaps() {}

  /**
   * Makes an empty map with the default capacities.
   *
   * @param inlineKey the order and the encoding of the keys
   * @param vectorCount {@code true} for the vector count of bbtree-vector, {@code false} for the
   *     scalar scan
   * @param narrowInnerWindows {@code true} for 4-byte windows in the inner nodes
   * @param <K> the key type
   * @param <V> the value type
   * @return the map
   */
  public static <K, V> BBTreeMap<K, V> create(
      InlineKey<K> inlineKey, boolean vectorCount, boolean narrowInnerWindows) {
    return new BBTreeMap<>(
        inlineKey,
        inlineKey,
        BBTreeMap.DEFAULT_LEAF_CAPACITY,
        BBTreeMap.DEFAULT_INNER_CAPACITY,
        vectorCount ? VectorWindowScan.COUNT : WindowScan.SCALAR,
        narrowInnerWindows);
  }
}
