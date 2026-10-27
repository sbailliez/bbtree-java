package io.github.sb.bbtree;

import java.util.Arrays;

/**
 * An inner node. Child {@code i} holds the keys {@code k} with {@code keys[i - 1] <= k <
 * keys[i]}, so an inner node with {@code count} separators has {@code count + 1} children.
 */
final class Inner extends Node {

  final Node[] children;

  /**
   * The first 4 bytes of each 8-byte window of {@link #windows}, or {@code null}. A search uses
   * them only when the node has both fences. The slots from {@code count} to the end hold {@link
   * Integer#MAX_VALUE}, so that a vector scan can compare whole registers with no mask. The length
   * is a multiple of {@link BBTreeMap#NARROW_ALIGNMENT}.
   */
  final int[] narrow;

  Inner(int capacity, boolean inline, boolean narrowWindows) {
    super(capacity, inline);
    this.children = new Node[capacity + 1];
    if (inline && narrowWindows) {
      int align = BBTreeMap.NARROW_ALIGNMENT;
      this.narrow = new int[(capacity + align - 1) / align * align];
      Arrays.fill(narrow, Integer.MAX_VALUE);
    } else {
      this.narrow = null;
    }
  }
}
