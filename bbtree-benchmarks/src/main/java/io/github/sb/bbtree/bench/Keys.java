package io.github.sb.bbtree.bench;

import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Generates the keys of the benchmarks. The same seed gives the same keys, so every map gets
 * the same work.
 */
final class Keys {

  private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

  private Keys() {}

  /** Gives {@code n} distinct keys of a type in random order. */
  static Object[] distinct(String keyType, int n, long seed) {
    if (keyType.equals("dense")) {
      // The integers 0 to n - 1, as the IDs of a table.
      Object[] keys = new Object[n];
      for (int i = 0; i < n; i++) {
        keys[i] = (long) i;
      }
      shuffle(keys, seed);
      return keys;
    }
    SplittableRandom random = new SplittableRandom(seed);
    clock = UUID7_START_MILLIS;
    java.util.Set<Object> seen = new java.util.HashSet<>(2 * n);
    Object[] keys = new Object[n];
    int i = 0;
    while (i < n) {
      Object k = next(keyType, random);
      if (seen.add(k)) {
        keys[i++] = k;
      }
    }
    return keys;
  }

  private static Object next(String keyType, SplittableRandom random) {
    return switch (keyType) {
      case "long" -> random.nextLong();
      case "uuid4" -> uuid4(random);
      case "uuid7" -> uuid7(random);
      case "string" -> url(random);
      default -> throw new IllegalArgumentException("unknown key type " + keyType);
    };
  }

  /** 2025-10-09, in milliseconds since the epoch: the first timestamp of the UUID v7 keys. */
  private static final long UUID7_START_MILLIS = 1_760_000_000_000L;

  /** The timestamp of the last UUID v7 key. */
  private static long clock;

  /** A random UUID (RFC 9562 version 4). */
  private static UUID uuid4(SplittableRandom random) {
    long msb = (random.nextLong() & ~0xF000L) | 0x4000L;
    long lsb = (random.nextLong() & ~(0xC000_0000_0000_0000L)) | 0x8000_0000_0000_0000L;
    return new UUID(msb, lsb);
  }

  /**
   * A time-ordered UUID (RFC 9562 version 7): 48 bits of milliseconds, then random bits. The
   * clock moves forward by 0 or 1 millisecond for each key, so the keys come in time order, as
   * new primary keys do.
   */
  private static UUID uuid7(SplittableRandom random) {
    clock += random.nextInt(2);
    long msb = (clock << 16) | 0x7000L | random.nextInt(0x1000);
    long lsb = (random.nextLong() & ~(0xC000_0000_0000_0000L)) | 0x8000_0000_0000_0000L;
    return new UUID(msb, lsb);
  }

  /** A URL-like key: a long shared prefix, then a path of 8 to 16 characters. */
  private static String url(SplittableRandom random) {
    StringBuilder sb = new StringBuilder("https://example.com/articles/");
    int n = 8 + random.nextInt(9);
    for (int i = 0; i < n; i++) {
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }

  /** Shuffles an array in place. */
  static void shuffle(Object[] keys, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    for (int i = keys.length - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      Object t = keys[i];
      keys[i] = keys[j];
      keys[j] = t;
    }
  }
}
