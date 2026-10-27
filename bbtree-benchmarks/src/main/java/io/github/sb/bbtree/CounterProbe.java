package io.github.sb.bbtree;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.SplittableRandom;

/**
 * One operation mode on a map of 1M {@code long} keys, for a count of hardware events with
 * {@code perf stat}. The program builds the map, warms up the JIT, prints {@code READY}, waits 1.5
 * seconds, runs the operations, and prints the time per operation. {@code counter-probe.sh}
 * attaches {@code perf stat} after {@code READY}, so the counts cover the operations only.
 *
 * <p>Arguments: the search variant of {@link SearchVariants}, the mode, and the number of
 * operations (default 4,000,000). The modes:
 *
 * <ul>
 *   <li>{@code hit-warm}: present keys, as new {@link Long} objects in allocation order, which
 *       stay in the cache.
 *   <li>{@code hit-cold}: present keys, as the stored objects in random order, so each lookup
 *       starts with a cache miss on the key.
 *   <li>{@code miss-cold}: absent keys in random order.
 *   <li>{@code insert}: new keys in allocation order.
 * </ul>
 */
public final class CounterProbe {

  private CounterProbe() {}

  /**
   * Runs the probe.
   *
   * @param args the variant, the mode, and optionally the number of operations
   * @throws InterruptedException if the wait is interrupted
   */
  public static void main(String[] args) throws InterruptedException {
    String variant = args[0];
    String mode = args[1];
    int n = 1_000_000;
    int ops = Integer.parseInt(args.length > 2 ? args[2] : "4000000");
    SplittableRandom random = new SplittableRandom(3);
    Long[] keys = new Long[n];
    Long[] fresh = new Long[ops];
    for (int i = 0; i < n; i++) {
      keys[i] = random.nextLong();
    }
    for (int i = 0; i < ops; i++) {
      fresh[i] = random.nextLong();
    }
    BBTreeMap<Long, Long> map = SearchVariants.create(InlineKey.LONG, variant);
    for (Long k : keys) {
      map.put(k, k);
    }
    SplittableRandom pick = new SplittableRandom(7);
    Long[] present = new Long[ops];
    Long[] warmPresent = new Long[ops];
    for (int i = 0; i < ops; i++) {
      present[i] = keys[pick.nextInt(n)];
      warmPresent[i] = Long.valueOf(present[i].longValue());
    }
    Long[] spread = fresh.clone();
    Collections.shuffle(Arrays.asList(spread), new Random(9));
    Long[] in =
        switch (mode) {
          case "hit-warm" -> warmPresent;
          case "hit-cold" -> present;
          case "miss-cold" -> spread;
          case "insert" -> fresh;
          default -> throw new IllegalArgumentException("unknown mode " + mode);
        };
    long found = 0;
    for (int round = 0; round < 3; round++) {
      if (mode.equals("insert")) {
        BBTreeMap<Long, Long> warmUp = SearchVariants.create(InlineKey.LONG, variant);
        for (Long k : keys) {
          warmUp.put(k, k);
        }
        for (int i = 0; i < ops / 4; i++) {
          warmUp.put(in[i], in[i]);
        }
      } else {
        for (int i = 0; i < ops; i++) {
          found += map.containsKey(in[i]) ? 1 : 0;
        }
      }
    }
    System.gc();
    System.out.println("READY");
    System.out.flush();
    Thread.sleep(1500);
    long start = System.nanoTime();
    if (mode.equals("insert")) {
      for (int i = 0; i < ops; i++) {
        map.put(in[i], in[i]);
      }
    } else {
      for (int i = 0; i < ops; i++) {
        found += map.containsKey(in[i]) ? 1 : 0;
      }
    }
    long elapsed = System.nanoTime() - start;
    System.out.printf(
        "%s %s: %.1f ns/op (%d found)%n", variant, mode, (double) elapsed / ops, found);
  }
}
