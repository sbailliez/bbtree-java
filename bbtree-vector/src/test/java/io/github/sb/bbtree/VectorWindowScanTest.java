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
      long expected = WindowScan.SCALAR.scan(windows, n, target);
      String at = "n " + n + ", target " + target + ", " + Arrays.toString(windows);
      assertEquals(expected, VectorWindowScan.COUNT.scan(windows, n, target), "COUNT " + at);
      assertEquals(expected, VectorWindowScan.SCAN.scan(windows, n, target), "SCAN " + at);
    }
  }
}
