package io.github.sb.bbtree;

import java.util.Arrays;

/**
 * An inner node. Child {@code i} holds the keys {@code k} with {@code keys[i - 1] <= k <
 * keys[i]}, so an inner node with {@code count} separators has {@code count + 1} children.
 */
final class Inner extends Node {

  /** The number of slots of each child in {@link #slots}. */
  static final int SLOTS = 3;

  /**
   * The children, in triples: {@code slots[3 * i]} is child {@code i}, {@code slots[3 * i + 1]}
   * its head, and {@code slots[3 * i + 2]} its body. A lookup reads the head and the body of a
   * child from here, from the same cache line as the child reference, and does not load the child
   * object. The head and the body of a node never change, so a triple changes only when the child
   * changes.
   */
  final Object[] slots;

  /**
   * The first 4 bytes of each 8-byte window of the head, or {@code null}. A search uses them only
   * when the node has both fences. The slots from {@code count} to the end hold {@link
   * Integer#MAX_VALUE}, so that a vector scan can compare whole registers with no mask. The length
   * is a multiple of {@link BBTreeMap#NARROW_ALIGNMENT}.
   */
  final int[] narrow;

  Inner(int capacity, boolean inline, boolean narrowWindows) {
    super(capacity, inline, false);
    this.slots = new Object[SLOTS * (capacity + 1)];
    if (inline && narrowWindows) {
      int align = BBTreeMap.NARROW_ALIGNMENT;
      this.narrow = new int[(capacity + align - 1) / align * align];
      Arrays.fill(narrow, Integer.MAX_VALUE);
    } else {
      this.narrow = null;
    }
  }

  @Override
  Object body() {
    return slots;
  }

  /** Gives child {@code i}. */
  Node child(int i) {
    return (Node) slots[SLOTS * i];
  }

  /** Gives child {@code i} of the inner node with these slots. */
  static Node child(Object[] slots, int i) {
    return (Node) slots[SLOTS * i];
  }

  /** Gives the head of child {@code i} of the inner node with these slots. */
  static long[] childHead(Object[] slots, int i) {
    return (long[]) slots[SLOTS * i + 1];
  }

  /** Gives the body of child {@code i} of the inner node with these slots. */
  static Object childBody(Object[] slots, int i) {
    return slots[SLOTS * i + 2];
  }

  /** Sets child {@code i}, with its head and body. */
  void setChild(int i, Node child) {
    int s = SLOTS * i;
    slots[s] = child;
    slots[s + 1] = child == null ? null : child.head;
    slots[s + 2] = child == null ? null : child.body();
  }

  /** Copies children {@code [from, from + n)} of {@code src} to index {@code to} of {@code dst}. */
  static void copyChildren(Inner src, int from, Inner dst, int to, int n) {
    System.arraycopy(src.slots, SLOTS * from, dst.slots, SLOTS * to, SLOTS * n);
  }

  /** Clears children {@code [from, to)}. */
  void clearChildren(int from, int to) {
    Arrays.fill(slots, SLOTS * from, SLOTS * to, null);
  }

  /** Gives the number of child positions: the capacity plus one. */
  int childSlots() {
    return slots.length / SLOTS;
  }
}
