package io.github.sb.bbtree;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Window scans with the Java Vector API. Each loop step compares one register of windows with
 * the target, and the tail after the last full register is scalar.
 */
final class VectorWindowScan {

  static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;

  private VectorWindowScan() {}

  /**
   * The count of the Rust bbtree: compare every window, count the smaller ones and the equal
   * ones, with no branch on the data.
   */
  static final WindowScan COUNT =
      (windows, n, target) -> {
        LongVector t = LongVector.broadcast(SPECIES, target);
        int bound = SPECIES.loopBound(n);
        int lt = 0;
        int le = 0;
        int i = 0;
        for (; i < bound; i += SPECIES.length()) {
          LongVector v = LongVector.fromArray(SPECIES, windows, i);
          lt += v.lt(t).trueCount();
          le += v.compare(VectorOperators.LE, t).trueCount();
        }
        for (; i < n; i++) {
          long w = windows[i];
          lt += w < target ? 1 : 0;
          le += w <= target ? 1 : 0;
        }
        return WindowScan.pack(lt, le);
      };

  /**
   * A scan that stops at the first register with a window that is not smaller than the target.
   * The windows are sorted, so the first such lane is the position.
   */
  static final WindowScan SCAN =
      (windows, n, target) -> {
        LongVector t = LongVector.broadcast(SPECIES, target);
        int bound = SPECIES.loopBound(n);
        int lo = 0;
        while (lo < bound) {
          VectorMask<Long> notSmaller =
              LongVector.fromArray(SPECIES, windows, lo).compare(VectorOperators.GE, t);
          if (notSmaller.anyTrue()) {
            lo += notSmaller.firstTrue();
            break;
          }
          lo += SPECIES.length();
        }
        if (lo >= bound) {
          while (lo < n && windows[lo] < target) {
            lo++;
          }
        }
        int hi = lo;
        while (hi < n && windows[hi] == target) {
          hi++;
        }
        return WindowScan.pack(lo, hi);
      };
}
