package io.github.sb.bbtree;

import java.util.Comparator;
import java.util.UUID;

/**
 * An order-preserving byte encoding of a key type, which lets the nodes of a {@link BBTreeMap}
 * search fixed-width integers in place of the keys themselves.
 *
 * <p>Each key has an encoding: a sequence of bytes whose unsigned lexicographic order, with a
 * shorter sequence before every longer sequence that starts with it, is the order of this
 * comparator. A node keeps, for each of its keys, the <em>window</em> of 8 bytes that starts
 * right after the prefix that all keys of the node share. The node compares these windows as
 * unsigned 64-bit integers, and reads a key object only when two windows tie.
 *
 * <p>An implementation must obey these contracts:
 *
 * <ol>
 *   <li>{@code compare(a, b) < 0} if and only if the encoding of {@code a} sorts before the
 *       encoding of {@code b}.
 *   <li>{@link #window(Object, int)} gives the bytes {@code offset .. offset + 7} of the
 *       encoding, big-endian, with zero bytes past the end of the encoding.
 *   <li>{@link #commonPrefix(Object, Object)} gives the length, in bytes, of the longest common
 *       prefix of the two encodings.
 *   <li>If {@link #fixedLength()} is not negative, every encoding has exactly that length.
 * </ol>
 *
 * <p>{@code InlineKeyContractTest} in the test sources gives a property check that you can use
 * for your own implementation.
 *
 * @param <K> the key type
 */
public interface InlineKey<K> extends Comparator<K> {

  /** {@link Long} keys in natural order. The encoding is 8 bytes, with the sign bit flipped. */
  InlineKey<Long> LONG = InlineKeys.LongKey.INSTANCE;

  /** {@link Integer} keys in natural order. The encoding is 4 bytes, with the sign bit flipped. */
  InlineKey<Integer> INTEGER = InlineKeys.IntegerKey.INSTANCE;

  /**
   * {@link String} keys in natural order ({@link String#compareTo}). The encoding is the UTF-16
   * code units, big-endian, which sort as {@code compareTo} does.
   */
  InlineKey<String> STRING = InlineKeys.StringKey.INSTANCE;

  /** {@code byte[]} keys in unsigned lexicographic order ({@link java.util.Arrays#compareUnsigned}). */
  InlineKey<byte[]> BYTES = InlineKeys.BytesKey.INSTANCE;

  /**
   * {@link UUID} keys in natural order ({@link UUID#compareTo}, which compares the two halves as
   * signed longs). The encoding is 16 bytes, with the sign bit of each half flipped.
   */
  InlineKey<UUID> UUID = InlineKeys.UuidKey.INSTANCE;

  /**
   * Gives the bytes {@code offset .. offset + 7} of the encoding of {@code key}, as a big-endian
   * unsigned long. The bytes past the end of the encoding are zero.
   *
   * @param key the key, never {@code null}
   * @param offset the start of the window, in bytes, zero or more
   * @return the window
   */
  long window(K key, int offset);

  /**
   * Gives the length, in bytes, of the longest common prefix of the encodings of two keys.
   *
   * @param a a key, never {@code null}
   * @param b a key, never {@code null}
   * @return the length of the common prefix
   */
  int commonPrefix(K a, K b);

  /**
   * Gives the length of every encoding, or {@code -1} if the encodings have different lengths.
   * When a window reaches the end of a fixed-length encoding, two equal windows mean two equal
   * keys, and the node does not compare the keys.
   *
   * @return the fixed length in bytes, or {@code -1}
   */
  default int fixedLength() {
    return -1;
  }
}
