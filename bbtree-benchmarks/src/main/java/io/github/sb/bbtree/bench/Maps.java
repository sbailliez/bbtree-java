package io.github.sb.bbtree.bench;

import io.github.sb.bbtree.BBTreeMap;
import io.github.sb.bbtree.InlineKey;
import io.github.sb.bbtree.VectorBBTreeMaps;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/** The maps under comparison, by the name of the {@code impl} parameter. */
final class Maps {

  private Maps() {}

  @SuppressWarnings({"unchecked", "rawtypes"})
  static Map<Object, Object> create(String impl, String keyType) {
    return switch (impl) {
      case "bbtree" -> (Map) new BBTreeMap<>(inlineKey(keyType));
      case "bbtree-noinline" -> new BBTreeMap<>();
      case "bbtree-vector-count" ->
          (Map) VectorBBTreeMaps.create(inlineKey(keyType), VectorBBTreeMaps.Mode.COUNT);
      case "bbtree-vector-scan" ->
          (Map) VectorBBTreeMaps.create(inlineKey(keyType), VectorBBTreeMaps.Mode.SCAN);
      case "treemap" -> new TreeMap<>();
      case "skiplist" -> new ConcurrentSkipListMap<>();
      case "rwlock-treemap" -> new ReadWriteLockedMap<>(new TreeMap<>());
      default -> sized(impl, keyType);
    };
  }

  /** Makes a map named {@code bbtree-<leaf capacity>x<inner capacity>}, such as bbtree-16x64. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Map<Object, Object> sized(String impl, String keyType) {
    if (!impl.startsWith("bbtree-") || !impl.contains("x")) {
      throw new IllegalArgumentException("unknown impl " + impl);
    }
    String[] capacities = impl.substring("bbtree-".length()).split("x");
    return (Map)
        new BBTreeMap<>(
            (InlineKey) inlineKey(keyType),
            Integer.parseInt(capacities[0]),
            Integer.parseInt(capacities[1]));
  }

  private static InlineKey<?> inlineKey(String keyType) {
    return switch (keyType) {
      case "long" -> InlineKey.LONG;
      case "string" -> InlineKey.STRING;
      case "uuid4", "uuid7" -> InlineKey.UUID;
      default -> throw new IllegalArgumentException("unknown key type " + keyType);
    };
  }
}
