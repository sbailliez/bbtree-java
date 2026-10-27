package io.github.sb.bbtree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Each factory method makes a working map of its kind. */
class BBTreesTest {

  @Test
  void eachKindWorks() {
    BBTreeMap<String, Integer> natural = BBTrees.naturalOrder();
    natural.put("b", 2);
    natural.put("a", 1);
    assertEquals("a", natural.firstKey());

    BBTreeMap<String, Integer> reversed = BBTrees.ordered(Comparator.reverseOrder());
    reversed.put("a", 1);
    reversed.put("b", 2);
    assertEquals("b", reversed.firstKey());
    assertThrows(NullPointerException.class, () -> BBTrees.ordered(null));

    BBTreeMap<UUID, String> uuids = BBTrees.inline(InlineKey.UUID);
    UUID id = new UUID(1, 2);
    uuids.put(id, "x");
    assertEquals("x", uuids.get(id));

    LongBBTreeMap<String> longKeys = BBTrees.longKeys();
    longKeys.put(42, "answer");
    assertEquals("answer", longKeys.get(42));
    assertNull(longKeys.get(43));

    LongLongBBTreeMap longToLong = BBTrees.longToLong();
    longToLong.put(42, 7, -1);
    assertEquals(7, longToLong.get(42, -1));

    PackedLongMap packed = BBTrees.packed();
    packed.put(42, 7, -1);
    assertEquals(7, packed.get(42, -1));
    assertEquals(-1, packed.get(43, -1));
  }
}
