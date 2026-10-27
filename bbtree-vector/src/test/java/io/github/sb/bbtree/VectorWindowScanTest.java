package io.github.sb.bbtree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The vector scans must give the same position as the scalar scan for every input. */
class VectorWindowScanTest {

  @Test
  void vectorScansMatchTheScalarScan() {
    Random random = new Random(11);
    for (int round = 0; round < 20_000; round++) {
      int n = random.nextInt(70);
      long[] windows = new long[n + random.nextInt(8)];
      // Few distinct values, so that runs of equal windows are frequent. The values span the
      // sign bit, as the stored windows do.
      for (int i = 0; i < windows.length; i++) {
        windows[i] = (random.nextInt(9) - 4) * (Long.MAX_VALUE / 4);
      }
      Arrays.sort(windows, 0, n);
      long target = (random.nextInt(11) - 5) * (Long.MAX_VALUE / 4);
      // The windows of a node start at slot Node.W of its head.
      long[] head = new long[Node.W + windows.length];
      System.arraycopy(windows, 0, head, Node.W, windows.length);
      long expected = WindowScan.SCALAR.scan(windows, 0, n, target);
      String at = "n " + n + ", target " + target + ", " + Arrays.toString(windows);
      assertEquals(expected, WindowScan.SCALAR.scan(head, Node.W, n, target), "SCALAR " + at);
      assertEquals(expected, VectorWindowScan.COUNT.scan(windows, 0, n, target), "COUNT " + at);
      assertEquals(expected, VectorWindowScan.SCAN.scan(windows, 0, n, target), "SCAN " + at);
      assertEquals(expected, VectorWindowScan.COUNT.scan(head, Node.W, n, target), "COUNT " + at);
      assertEquals(expected, VectorWindowScan.SCAN.scan(head, Node.W, n, target), "SCAN " + at);
    }
  }

  @Test
  void vectorScansOfNarrowWindowsMatchTheScalarScan() {
    Random random = new Random(12);
    int[] values = {Integer.MIN_VALUE, -7, -1, 0, 1, 5, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
    for (int round = 0; round < 20_000; round++) {
      int capacity = 4 + random.nextInt(130);
      int align = BBTreeMap.NARROW_ALIGNMENT;
      int[] windows = new int[(capacity + align - 1) / align * align];
      Arrays.fill(windows, Integer.MAX_VALUE);
      int n = random.nextInt(capacity + 1);
      for (int i = 0; i < n; i++) {
        windows[i] = values[random.nextInt(values.length)];
      }
      Arrays.sort(windows, 0, n);
      int target = values[random.nextInt(values.length)];
      long expected = WindowScan.SCALAR.scan(windows, n, target);
      String at = "n " + n + ", target " + target + ", " + Arrays.toString(windows);
      assertEquals(expected, VectorWindowScan.COUNT.scan(windows, n, target), "COUNT " + at);
      assertEquals(expected, VectorWindowScan.SCAN.scan(windows, n, target), "SCAN " + at);
    }
  }
}
