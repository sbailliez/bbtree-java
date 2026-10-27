package io.github.sb.bbtree;

/**
 * A leaf node: the entries, and the link to the next leaf in key order. A leaf keeps its values in
 * {@link #values}, or, in a map of primitive {@code long} values, in {@link #longValues}. Exactly
 * one of the two is not {@code null}.
 */
final class Leaf extends Node {

  /** The values, or {@code null} in a map of primitive values. */
  final Object[] values;

  /** The primitive values, or {@code null}. A primitive value needs no object of its own. */
  final long[] longValues;

  /** The next leaf in key order, or {@code null} for the last leaf. */
  Leaf next;

  Leaf(int capacity, boolean inline, boolean primitiveValues) {
    super(capacity, inline);
    this.values = primitiveValues ? null : new Object[capacity];
    this.longValues = primitiveValues ? new long[capacity] : null;
  }
}
