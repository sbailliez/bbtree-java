package io.github.sb.bbtree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks each built-in {@link InlineKey} against a reference encoding: the comparator, the
 * windows at every offset, the common prefix, and the fixed length. To check your own {@link
 * InlineKey}, add a case with a reference encoder and a key generator.
 */
class InlineKeyContractTest {

  private static final int PAIRS = 20_000;

  record Case<K>(
      String name,
      InlineKey<K> inline,
      Function<K, byte[]> encode,
      Function<Random, K> generate,
      List<K> edges) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of(
            new Case<>(
                "LONG",
                InlineKey.LONG,
                v -> ByteBuffer.allocate(8).putLong(v ^ Long.MIN_VALUE).array(),
                InlineKeyContractTest::smallOrLargeLong,
                List.of(Long.MIN_VALUE, -1L, 0L, 1L, 255L, 256L, Long.MAX_VALUE))),
        Arguments.of(
            new Case<>(
                "INTEGER",
                InlineKey.INTEGER,
                v -> ByteBuffer.allocate(4).putInt(v ^ Integer.MIN_VALUE).array(),
                r -> r.nextBoolean() ? r.nextInt() : r.nextInt(512) - 256,
                List.of(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE))),
        Arguments.of(
            new Case<>(
                "UUID",
                InlineKey.UUID,
                u ->
                    ByteBuffer.allocate(16)
                        .putLong(u.getMostSignificantBits() ^ Long.MIN_VALUE)
                        .putLong(u.getLeastSignificantBits() ^ Long.MIN_VALUE)
                        .array(),
                r -> new UUID(r.nextInt(4) == 0 ? 7L : r.nextLong(), r.nextLong()),
                List.of(
                    new UUID(0, 0),
                    new UUID(-1, -1),
                    new UUID(Long.MIN_VALUE, Long.MAX_VALUE),
                    new UUID(7, 0),
                    new UUID(7, -1)))),
        Arguments.of(
            new Case<>(
                "STRING",
                InlineKey.STRING,
                InlineKeyContractTest::utf16,
                InlineKeyContractTest::string,
                List.of("", "a", "a\0", "ab", "Ā", "ÿ", "￿", "https://example.com/"))),
        Arguments.of(
            new Case<>(
                "BYTES",
                InlineKey.BYTES,
                b -> b,
                InlineKeyContractTest::bytes,
                List.of(new byte[0], new byte[] {0}, new byte[] {-1}, new byte[] {1, 2, 3}))));
  }

  @ParameterizedTest
  @MethodSource("cases")
  <K> void obeysTheContract(Case<K> c) {
    Random random = new Random(42);
    List<K> keys = new ArrayList<>(c.edges());
    for (int i = 0; i < 200; i++) {
      keys.add(c.generate().apply(random));
    }
    for (K a : keys) {
      for (K b : keys) {
        checkPair(c, a, b);
      }
    }
    for (int i = 0; i < PAIRS; i++) {
      K a = c.generate().apply(random);
      K b = random.nextInt(4) == 0 ? a : c.generate().apply(random);
      checkPair(c, a, b);
    }
  }

  private static <K> void checkPair(Case<K> c, K a, K b) {
    byte[] ea = c.encode().apply(a);
    byte[] eb = c.encode().apply(b);
    assertEquals(
        Integer.signum(Arrays.compareUnsigned(ea, eb)),
        Integer.signum(c.inline().compare(a, b)),
        () -> c + ": the comparator does not follow the encoding for " + a + " and " + b);
    int expectedPrefix = Arrays.mismatch(ea, eb);
    if (expectedPrefix < 0) {
      expectedPrefix = ea.length;
    }
    assertEquals(expectedPrefix, c.inline().commonPrefix(a, b), () -> c + ": commonPrefix");
    if (c.inline().fixedLength() >= 0) {
      assertEquals(c.inline().fixedLength(), ea.length, () -> c + ": fixedLength");
    }
    int maxOffset = Math.max(ea.length, eb.length) + 2;
    for (int offset = 0; offset <= maxOffset; offset++) {
      int at = offset;
      assertEquals(
          reference(ea, offset), c.inline().window(a, offset), () -> c + ": window at " + at);
    }
  }

  private static long reference(byte[] encoding, int offset) {
    long w = 0;
    for (int i = 0; i < Long.BYTES; i++) {
      int at = offset + i;
      w = (w << 8) | (at < encoding.length ? encoding[at] & 0xFFL : 0L);
    }
    return w;
  }

  /** The UTF-16 code units, big-endian. Unlike {@code getBytes}, it keeps lone surrogates. */
  private static byte[] utf16(String s) {
    ByteBuffer b = ByteBuffer.allocate(2 * s.length());
    for (int i = 0; i < s.length(); i++) {
      b.putChar(s.charAt(i));
    }
    return b.array();
  }

  private static long smallOrLargeLong(Random r) {
    return switch (r.nextInt(3)) {
      case 0 -> r.nextLong();
      case 1 -> r.nextInt(1 << 20) - (1 << 19);
      default -> (r.nextLong() & 0xFFFF_FFFF_0000_0000L) | r.nextInt(4);
    };
  }

  private static String string(Random r) {
    Supplier<Character> unit =
        () ->
            switch (r.nextInt(4)) {
              case 0 -> (char) ('a' + r.nextInt(3));
              case 1 -> (char) r.nextInt(0x300);
              case 2 -> (char) (0xff + r.nextInt(3));
              default -> (char) r.nextInt(0x10000);
            };
    StringBuilder sb = new StringBuilder(r.nextBoolean() ? "https://example.com/" : "");
    int n = r.nextInt(12);
    for (int i = 0; i < n; i++) {
      sb.append(unit.get());
    }
    return sb.toString();
  }

  private static byte[] bytes(Random r) {
    byte[] b = new byte[r.nextInt(20)];
    for (int i = 0; i < b.length; i++) {
      b[i] = (byte) (r.nextInt(4) == 0 ? r.nextInt(3) : r.nextInt(256));
    }
    return b;
  }
}
