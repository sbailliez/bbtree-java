package io.github.sb.bbtree;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Window scans with the Java Vector API. Each loop step compares one register of windows with
 * the target.
 *
 * <p>The 8-byte windows of a node have no padding, so the scans of {@code long[]} end with a
 * scalar tail after the last full register. The 4-byte windows of an inner node hold {@link
 * Integer#MAX_VALUE} after the live slots, up to a multiple of {@link BBTreeMap#NARROW_ALIGNMENT},
 * so the scans of {@code int[]} read whole registers and need no tail.
 */
final class VectorWindowScan {

  static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
  static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;

  private VectorWindowScan() {}

  /**
   * The count of the Rust bbtree: compare every window, and count the smaller ones and the equal
   * ones with no branch on the data. Each compare subtracts its mask (all ones for true) from lane
   * counters, and the lanes are added once at the end.
   */
  static final WindowScan COUNT =
      new WindowScan() {
        @Override
        public long scan(long[] windows, int from, int n, long target) {
          LongVector t = LongVector.broadcast(SPECIES, target);
          LongVector below = LongVector.zero(SPECIES);
          LongVector equal = LongVector.zero(SPECIES);
          int bound = SPECIES.loopBound(n);
          int i = 0;
          for (; i < bound; i += SPECIES.length()) {
            LongVector v = LongVector.fromArray(SPECIES, windows, from + i);
            below = below.sub(v.compare(VectorOperators.LT, t).toVector());
            equal = equal.sub(v.compare(VectorOperators.EQ, t).toVector());
          }
          int lt = (int) below.reduceLanes(VectorOperators.ADD);
          int eq = (int) equal.reduceLanes(VectorOperators.ADD);
          for (; i < n; i++) {
            long w = windows[from + i];
            lt += w < target ? 1 : 0;
            eq += w == target ? 1 : 0;
          }
          return WindowScan.pack(lt, lt + eq);
        }

        @Override
        public long scan(int[] windows, int n, int target) {
          IntVector t = IntVector.broadcast(INTS, target);
          IntVector below = IntVector.zero(INTS);
          IntVector equal = IntVector.zero(INTS);
          for (int i = 0; i < n; i += INTS.length()) {
            IntVector v = IntVector.fromArray(INTS, windows, i);
            below = below.sub(v.compare(VectorOperators.LT, t).toVector());
            equal = equal.sub(v.compare(VectorOperators.EQ, t).toVector());
          }
          int lt = below.reduceLanes(VectorOperators.ADD);
          // The padding equals the target when the target is the largest window.
          int le = Math.min(n, lt + equal.reduceLanes(VectorOperators.ADD));
          return WindowScan.pack(Math.min(lt, n), le);
        }
      };

  /**
   * A scan that stops at the first register with a window not smaller than the target. The
   * windows are sorted, so the first such lane is the position.
   */
  static final WindowScan SCAN =
      new WindowScan() {
        @Override
        public long scan(long[] windows, int from, int n, long target) {
          LongVector t = LongVector.broadcast(SPECIES, target);
          int bound = SPECIES.loopBound(n);
          int lo = 0;
          while (lo < bound) {
            VectorMask<Long> notSmaller =
                LongVector.fromArray(SPECIES, windows, from + lo).compare(VectorOperators.GE, t);
            if (notSmaller.anyTrue()) {
              lo += notSmaller.firstTrue();
              break;
            }
            lo += SPECIES.length();
          }
          if (lo >= bound) {
            while (lo < n && windows[from + lo] < target) {
              lo++;
            }
          }
          int hi = lo;
          while (hi < n && windows[from + hi] == target) {
            hi++;
          }
          return WindowScan.pack(lo, hi);
        }

        @Override
        public long scan(int[] windows, int n, int target) {
          IntVector t = IntVector.broadcast(INTS, target);
          int lo = 0;
          while (lo < n) {
            VectorMask<Integer> notSmaller =
                IntVector.fromArray(INTS, windows, lo).compare(VectorOperators.GE, t);
            if (notSmaller.anyTrue()) {
              lo += notSmaller.firstTrue();
              break;
            }
            lo += INTS.length();
          }
          lo = Math.min(lo, n);
          int hi = lo;
          while (hi < n && windows[hi] == target) {
            hi++;
          }
          return WindowScan.pack(lo, hi);
        }
      };
}
