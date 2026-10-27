package io.github.sb.bbtree.bench;

import io.github.sb.bbtree.BBTreeMap;
import java.util.Map;

/** Builds maps from the key sets of the benchmarks, for tools outside this package. */
public final class KeySets {

  private KeySets() {}

  /**
   * Makes a bbtree map with inline keys and 8-byte windows in all nodes, filled with {@code
   * size} distinct keys of a type, in the order that the benchmarks use.
   *
   * @param keyType long, uuid4, uuid7 or string
   * @param size the number of keys
   * @return the map
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static BBTreeMap<Object, Object> filledMap(String keyType, int size) {
    Map<Object, Object> map = Maps.create("bbtree", keyType);
    for (Object k : Keys.distinct(keyType, size, 3)) {
      map.put(k, k);
    }
    return (BBTreeMap) map;
  }
}
