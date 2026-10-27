package io.github.sb.bbtree;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A node of the tree, with its version lock.
 *
 * <p>The version is even when the node is unlocked and odd while a writer holds it. A writer
 * increments the version when it locks the node and again when it unlocks it. A reader reads the
 * version, reads the node with plain loads, and then validates that the version did not change.
 * Thus a read writes no shared cache line.
 *
 * <p>All other fields are plain. A reader can see them torn while a writer changes them: a count
 * that is too large, a {@code null} key, or windows of the old and the new prefix. The reader
 * must clamp every index to the capacity, and must discard what it read when the validation
 * fails. The garbage collector keeps every node and every array alive while a reader holds it,
 * so a stale read never touches freed memory.
 */
abstract sealed class Node permits Leaf, Inner {

  private static final VarHandle VERSION;

  static {
    try {
      VERSION = MethodHandles.lookup().findVarHandle(Node.class, "version", long.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @SuppressWarnings("unused") // Read and written through VERSION.
  private volatile long version;

  /**
   * The window of each key, with the sign bit flipped so that a signed compare of two windows
   * gives their unsigned order. Unused when the map has no {@link InlineKey}.
   */
  final long[] windows;

  /** The keys, sorted. In a leaf, the entry keys. In an inner node, the separators. */
  final Object[] keys;

  /** The number of keys. */
  int count;

  /** The number of encoding bytes that all keys in {@code [low, high)} share. */
  int prefix;

  /** The inclusive lower fence, or {@code null} for no bound. */
  Object low;

  /** The exclusive upper fence, or {@code null} for no bound. */
  Object high;

  Node(int capacity, boolean inline) {
    this.windows = inline ? new long[capacity] : null;
    this.keys = new Object[capacity];
  }

  final int capacity() {
    return keys.length;
  }

  /** Gives the current version. An odd version means that a writer holds the node. */
  final long version() {
    return (long) VERSION.getAcquire(this);
  }

  static boolean isLocked(long version) {
    return (version & 1L) != 0;
  }

  /**
   * Gives {@code true} if no writer changed the node since the reader read {@code version}.
   * The fence keeps the plain reads before it from moving after the second read of the version.
   */
  final boolean validate(long version) {
    VarHandle.acquireFence();
    return (long) VERSION.getAcquire(this) == version;
  }

  /** Locks the node if its version is still {@code version}. */
  final boolean tryUpgrade(long version) {
    if (isLocked(version) || !VERSION.compareAndSet(this, version, version + 1)) {
      return false;
    }
    // Keep the plain writes that follow from becoming visible before the odd version.
    VarHandle.storeStoreFence();
    return true;
  }

  /** Locks the node and waits as long as necessary. */
  final void lock() {
    while (true) {
      long v = version();
      if (tryUpgrade(v)) {
        return;
      }
      Thread.onSpinWait();
    }
  }

  /** Unlocks the node. The release store publishes the plain writes before it. */
  final void unlock() {
    VERSION.setRelease(this, (long) VERSION.get(this) + 1);
  }

  final boolean isLeaf() {
    return this instanceof Leaf;
  }
}
