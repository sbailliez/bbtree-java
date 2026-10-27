package io.github.sb.bbtree;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A node of the tree, with its version lock.
 *
 * <p>The state that a lookup reads is in one {@code long[]}, the head: the version in slot
 * {@link #VERSION}, the count, the prefix and the kind of node in slot {@link #META}, and the
 * windows from slot {@link #W}. The version thus shares a cache line with the first windows. A
 * parent keeps the head and the body of each child next to the child (see {@link Inner}), so a
 * lookup reads the head of a child with no load of the child object. The node object keeps the
 * state that only writers, scans and window ties read: the keys and the fences.
 *
 * <p>The version is even when the node is unlocked and odd while a writer holds it. A writer
 * increments the version when it locks the node and again when it unlocks it. A reader reads the
 * version, reads the node with plain loads, and then validates that the version did not change.
 * Thus a read writes no shared cache line.
 *
 * <p>All other state is plain. A reader can see it torn while a writer changes it: a count that
 * is too large, a {@code null} key, or windows of the old and the new prefix. The reader must
 * clamp every index to the capacity, and must discard what it read when the validation fails. The
 * garbage collector keeps every node and every array alive while a reader holds it, so a stale
 * read never touches freed memory.
 */
abstract sealed class Node permits Leaf, Inner {

  /** Atomic access to the version, an element of the head. */
  static final VarHandle HEAD = MethodHandles.arrayElementVarHandle(long[].class);

  /** The slot of the version in the head. */
  static final int VERSION = 0;

  /** The slot of the count (bits 0 to 31), the prefix (bits 32 to 47) and the leaf flag. */
  static final int META = 1;

  /** The slot of the first window in the head. */
  static final int W = 2;

  private static final long COUNT_MASK = 0xFFFF_FFFFL;
  private static final long PREFIX_MASK = 0xFFFFL << 32;
  private static final long LEAF_FLAG = 1L << 48;

  /**
   * The version, the count, the prefix, and the window of each key from slot {@link #W}. A window
   * has its sign bit flipped, so that a signed compare of two windows gives their unsigned order.
   * Without an {@link InlineKey} the head has no windows.
   */
  final long[] head;

  /** The keys, sorted. In a leaf, the entry keys. In an inner node, the separators. */
  final Object[] keys;

  /** The inclusive lower fence, or {@code null} for no bound. */
  Object low;

  /** The exclusive upper fence, or {@code null} for no bound. */
  Object high;

  Node(int capacity, boolean inline, boolean leaf) {
    this.head = new long[W + (inline ? capacity : 0)];
    this.head[META] = leaf ? LEAF_FLAG : 0;
    this.keys = new Object[capacity];
  }

  /** Gives the body that a parent keeps next to this node: the children or the values. */
  abstract Object body();

  final int capacity() {
    return keys.length;
  }

  /** Gives true if the head has windows, which it has when the map has an {@link InlineKey}. */
  final boolean hasWindows() {
    return head.length > W;
  }

  /** Gives the number of keys. */
  final int count() {
    return (int) head[META];
  }

  /** Gives the number of keys of the node with this head. */
  static int count(long[] head) {
    return (int) head[META];
  }

  /** Sets the number of keys of a locked node. */
  final void setCount(int count) {
    head[META] = (head[META] & ~COUNT_MASK) | (count & COUNT_MASK);
  }

  /** Gives the number of encoding bytes that all keys in {@code [low, high)} share. */
  final int prefix() {
    return prefix(head);
  }

  /** Gives the prefix of the node with this head. */
  static int prefix(long[] head) {
    return (int) ((head[META] & PREFIX_MASK) >>> 32);
  }

  /** Sets the prefix of a locked node. */
  final void setPrefixLength(int prefix) {
    head[META] = (head[META] & ~PREFIX_MASK) | (((long) prefix << 32) & PREFIX_MASK);
  }

  /** Gives true if the node with this head is a leaf. */
  static boolean isLeaf(long[] head) {
    return (head[META] & LEAF_FLAG) != 0;
  }

  /** Gives the current version. An odd version means that a writer holds the node. */
  final long version() {
    return version(head);
  }

  /** Gives the current version of the node with this head. */
  static long version(long[] head) {
    return (long) HEAD.getAcquire(head, VERSION);
  }

  static boolean isLocked(long version) {
    return (version & 1L) != 0;
  }

  /**
   * Gives {@code true} if no writer changed the node since the reader read {@code version}.
   * The fence keeps the plain reads before it from moving after the second read of the version.
   */
  final boolean validate(long version) {
    return validate(head, version);
  }

  /** Does {@link #validate(long)} for the node with this head. */
  static boolean validate(long[] head, long version) {
    VarHandle.acquireFence();
    return (long) HEAD.getAcquire(head, VERSION) == version;
  }

  /** Locks the node if its version is still {@code version}. */
  final boolean tryUpgrade(long version) {
    if (isLocked(version) || !HEAD.compareAndSet(head, VERSION, version, version + 1)) {
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
    HEAD.setRelease(head, VERSION, (long) HEAD.get(head, VERSION) + 1);
  }

  /**
   * Unlocks a node that the writer did not change, at the version from before the lock. Thus the
   * readers that read the node before the lock stay valid, and do not start again from the root.
   */
  final void unlockUnchanged() {
    HEAD.setRelease(head, VERSION, (long) HEAD.get(head, VERSION) - 1);
  }

  final boolean isLeaf() {
    return this instanceof Leaf;
  }
}
