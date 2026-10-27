package io.github.sb.bbtree;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;

/** The built-in {@link InlineKey} implementations. */
final class InlineKeys {

  private InlineKeys() {}

  /** Shifts a left-aligned encoding so that byte {@code offset} becomes the first byte. */
  private static long shiftOut(long leftAligned, int offset) {
    return offset >= Long.BYTES ? 0L : leftAligned << (offset << 3);
  }

  private static int commonBytes(long a, long b) {
    return Long.numberOfLeadingZeros(a ^ b) >>> 3;
  }

  enum LongKey implements InlineKey<Long> {
    INSTANCE;

    private static long encode(long v) {
      return v ^ Long.MIN_VALUE;
    }

    @Override
    public int compare(Long a, Long b) {
      return Long.compare(a, b);
    }

    @Override
    public long window(Long key, int offset) {
      return shiftOut(encode(key), offset);
    }

    @Override
    public int commonPrefix(Long a, Long b) {
      return commonBytes(encode(a), encode(b));
    }

    @Override
    public int fixedLength() {
      return Long.BYTES;
    }
  }

  enum IntegerKey implements InlineKey<Integer> {
    INSTANCE;

    private static long encode(int v) {
      return Integer.toUnsignedLong(v ^ Integer.MIN_VALUE) << 32;
    }

    @Override
    public int compare(Integer a, Integer b) {
      return Integer.compare(a, b);
    }

    @Override
    public long window(Integer key, int offset) {
      return shiftOut(encode(key), offset);
    }

    @Override
    public int commonPrefix(Integer a, Integer b) {
      return Math.min(Integer.BYTES, commonBytes(encode(a), encode(b)));
    }

    @Override
    public int fixedLength() {
      return Integer.BYTES;
    }
  }

  enum UuidKey implements InlineKey<UUID> {
    INSTANCE;

    @Override
    public int compare(UUID a, UUID b) {
      return a.compareTo(b);
    }

    @Override
    public long window(UUID key, int offset) {
      long hi = key.getMostSignificantBits() ^ Long.MIN_VALUE;
      long lo = key.getLeastSignificantBits() ^ Long.MIN_VALUE;
      if (offset == 0) {
        return hi;
      }
      if (offset < Long.BYTES) {
        int bits = offset << 3;
        return (hi << bits) | (lo >>> (Long.SIZE - bits));
      }
      return shiftOut(lo, offset - Long.BYTES);
    }

    @Override
    public int commonPrefix(UUID a, UUID b) {
      int hi = commonBytes(a.getMostSignificantBits(), b.getMostSignificantBits());
      if (hi < Long.BYTES) {
        return hi;
      }
      return Long.BYTES + commonBytes(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    @Override
    public int fixedLength() {
      return 2 * Long.BYTES;
    }
  }

  enum StringKey implements InlineKey<String> {
    INSTANCE;

    private static long unit(String s, int index) {
      return index < s.length() ? s.charAt(index) : 0L;
    }

    @Override
    public int compare(String a, String b) {
      return a.compareTo(b);
    }

    @Override
    public long window(String key, int offset) {
      int c = offset >>> 1;
      if ((offset & 1) == 0) {
        return unit(key, c) << 48 | unit(key, c + 1) << 32 | unit(key, c + 2) << 16 | unit(key, c + 3);
      }
      return unit(key, c) << 56
          | unit(key, c + 1) << 40
          | unit(key, c + 2) << 24
          | unit(key, c + 3) << 8
          | unit(key, c + 4) >>> 8;
    }

    @Override
    public int commonPrefix(String a, String b) {
      int n = Math.min(a.length(), b.length());
      for (int i = 0; i < n; i++) {
        int x = a.charAt(i) ^ b.charAt(i);
        if (x != 0) {
          // The high byte of the code unit is shared when the difference is in the low byte.
          return 2 * i + (x < 0x100 ? 1 : 0);
        }
      }
      return 2 * n;
    }
  }

  enum BytesKey implements InlineKey<byte[]> {
    INSTANCE;

    private static final VarHandle LONG_BE =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    @Override
    public int compare(byte[] a, byte[] b) {
      return Arrays.compareUnsigned(a, b);
    }

    @Override
    public long window(byte[] key, int offset) {
      if (offset + Long.BYTES <= key.length) {
        return (long) LONG_BE.get(key, offset);
      }
      long w = 0;
      for (int i = 0; i < Long.BYTES; i++) {
        int at = offset + i;
        w = (w << 8) | (at < key.length ? key[at] & 0xFFL : 0L);
      }
      return w;
    }

    @Override
    public int commonPrefix(byte[] a, byte[] b) {
      int i = Arrays.mismatch(a, b);
      return i < 0 ? a.length : i;
    }
  }
}
