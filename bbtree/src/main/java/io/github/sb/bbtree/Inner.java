package io.github.sb.bbtree;

/**
 * An inner node. Child {@code i} holds the keys {@code k} with {@code keys[i - 1] <= k <
 * keys[i]}, so an inner node with {@code count} separators has {@code count + 1} children.
 */
final class Inner extends Node {

  final Node[] children;

  Inner(int capacity, boolean inline) {
    super(capacity, inline);
    this.children = new Node[capacity + 1];
  }
}
