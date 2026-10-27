package io.github.sb.bbtree;

/** A leaf node: the entries, and the link to the next leaf in key order. */
final class Leaf extends Node {

  final Object[] values;

  /** The next leaf in key order, or {@code null} for the last leaf. */
  Leaf next;

  Leaf(int capacity, boolean inline) {
    super(capacity, inline);
    this.values = new Object[capacity];
  }
}
