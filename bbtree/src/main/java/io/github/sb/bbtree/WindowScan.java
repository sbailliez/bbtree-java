package io.github.sb.bbtree;

/**
 * Finds where a target window goes among the sorted windows of a node. The scalar scan is the
 * default. The module bbtree-vector gives scans that use the Java Vector API.
 */
interface WindowScan {

  /**
   * Gives the position of {@code target} in {@code windows[0, n)}, which is sorted.
   *
   * @return {@code lo << 32 | hi}, where {@code lo} is the number of windows that are smaller than
   *     {@code target}, and {@code hi - lo} is the number of windows that are equal to it
   */
  long scan(long[] windows, int n, long target);

  /**
   * Gives the position of {@code target} in the 4-byte windows {@code windows[0, n)}, as {@link
   * #scan(long[], int, long)} does. The slots from {@code n} to the end of the array hold {@link
   * Integer#MAX_VALUE}, and the array length is a multiple of {@link BBTreeMap#NARROW_ALIGNMENT},
   * so a vector scan can read whole registers after {@code n} with no mask.
   */
  long scan(int[] windows, int n, int target);

  /** Packs the result of a scan. */
  static long pack(int lo, int hi) {
    return ((long) lo << 32) | hi;
  }

  /**
   * A linear scan that stops at the first window that is not smaller than the target. C2 does
   * not vectorize a count of all windows on AArch64, and this scan was 11% to 22% faster for
   * {@code get} in JMH.
   */
  WindowScan SCALAR =
      new WindowScan() {
        @Override
        public long scan(long[] windows, int n, long target) {
          int lo = 0;
          while (lo < n && windows[lo] < target) {
            lo++;
          }
          int hi = lo;
          while (hi < n && windows[hi] == target) {
            hi++;
          }
          return pack(lo, hi);
        }

        @Override
        public long scan(int[] windows, int n, int target) {
          int lo = 0;
          while (lo < n && windows[lo] < target) {
            lo++;
          }
          int hi = lo;
          while (hi < n && windows[hi] == target) {
            hi++;
          }
          return pack(lo, hi);
        }
      };
}
