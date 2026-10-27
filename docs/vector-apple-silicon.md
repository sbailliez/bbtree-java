# A vector node search for Apple silicon

This review answers one question: what node search with the Java Vector API gives the best
lookup speed on Apple silicon? It is a design review with measurements. The module
`bbtree-vector` implements the 4-byte inner windows. The last section gives the measured result.

## Summary

1. **With 8-byte windows, the gain comes from removing a branch, not from wide compares.** A
   register holds 2 windows. The branch-free vector count made a single `get` 4% to 17% faster in
   the map, because the scalar scan mispredicts its exit branch once on each level. `getMany` got
   no gain.
2. **Narrow windows in inner nodes give the gain.** With 2-byte or 4-byte windows, a node of 64
   separators takes 1 or 2 cache lines in place of 4, and a register holds 8 or 4 windows. Out of
   the cache, a node search took 21 to 25 ns in place of 41 ns.
3. **Use the count of the Rust bbtree, not a mask count per register.** Compare the full capacity,
   subtract the all-ones lanes into lane counters, and add the lanes once at the end. The count in
   `bbtree-vector` calls `trueCount()` on each register, which costs too much at 64 windows.
4. **Keep 8-byte windows in the leaves and for strings.** A leaf of `long` keys with 8-byte windows
   reads no key object. A narrower window would read one `Long` for each hit. String keys tie on
   23% to 41% of slots with 4-byte windows.

The estimated gain for `get` is approximately 5% on a map of 1M `long` keys, because its inner
nodes fit in the L2 cache. It grows to approximately 20% when the inner levels do not fit in the
cache, at approximately 100M keys. The estimate is in the last section, and it is not measured in
the map.

## The machine

Apple M5 Pro, Corretto 25.0.4. From `sysctl`:

| Fact | Value | What it means for the search |
|---|---|---|
| `hw.cachelinesize` | 128 bytes | 64 windows of 8 bytes take 4 lines. 64 windows of 2 bytes take 1 line. |
| `AdvSIMD` (NEON) | yes | 128-bit registers: 2 `long`, 4 `int`, or 8 `short` lanes. |
| `FEAT_SVE` | absent | No SVE outside the streaming mode. |
| `FEAT_SME`, `FEAT_SME2` | yes, 512-bit streaming vectors | HotSpot does not use SME. Java code cannot enter the streaming mode. |
| Cores | 6 performance, 12 efficiency | When the machine is busy, macOS can move a benchmark thread to an efficiency core. |

`LongVector.SPECIES_PREFERRED` is `Species[long, 2, S_128_BIT]` on this machine. Thus the Vector API
gives NEON and nothing wider.

## What the Rust bbtree does on AArch64

Decision D56 in the Rust design, and `NeonCount` in `vector_count.rs`:

- For each 16 bytes of windows, it does one `cmhi` (greater than) and one `cmeq` (equal) against the
  broadcast target.
- It subtracts the all-ones lanes into counters of the window width. At the end, it adds the lanes
  of each counter once, with `addv`.
- It compares the full capacity of the node with no mask. The slots after the live ones hold the
  largest window (invariant 4), so they never count. A mask for each register cost 8% to 18% of a
  lookup.
- The windows of integer keys are 2 bytes wide in leaves and inner nodes (D31). The key itself is
  in the node, so a tie costs one load from the same node.

On an M1 Pro, this count took a `u64` lookup in a map of 10M entries from 390 ns to 174 ns.

## Measurements

The machine also ran other builds at a load average between 5 and 10. The vector results were
stable (errors of ±0.2 to ±2 ns in the cache). The scalar results varied more, because the
early exit mispredicts a branch at a random place in each node.

### One node search, by window width

`WindowWidthBenchmark`: three quarters of the slots are live, and the others hold the largest
window. "In L1" uses 256 nodes. "Out of cache" uses 262,144 nodes, which take 16 to 128 MB.
Times are in ns per search.

| Capacity | Width | Scalar, in L1 | Vector, in L1 | Scalar, out of cache | Vector, out of cache |
|--:|--:|--:|--:|--:|--:|
| 32 | 8 bytes | 10.1 | 6.3 | 28.0 | 22.7 |
| 32 | 4 bytes | 8.7 | 4.4 | 24.2 | 8.6 |
| 32 | 2 bytes | 9.6 | 7.4 | 27.5 | 25.3 |
| 64 | 8 bytes | 11.1 | 11.9 | 40.7 | 56.9 |
| 64 | 4 bytes | 10.3 | 7.1 | 41.1 | 21.1 |
| 64 | 2 bytes | 11.0 | 7.4 | 41.1 | 25.3 |

Rows with a capacity of 32 and a width of 2 or 4 bytes are partly in the cache: 262,144 nodes of
64 to 128 bytes take 16 to 32 MB, which is close to the 16 MB L2 of the performance cores.

Results:

- With 8-byte windows, the vector count is faster at a capacity of 32 (6.3 against 10.1 ns in L1)
  and equal at 64. Out of the cache, at a capacity of 64, it is slower (57 against 41 ns), because
  it reads all 4 cache lines and the scan stops early.
- With 4-byte and 2-byte windows, the vector count is 1.5 to 2 times faster than the scalar scan
  in L1, and 1.6 to 2.8 times faster out of the cache.
- At a capacity of 64, 4-byte windows (2 lines) and 2-byte windows (1 line) give almost the same
  time out of the cache. The first cache miss costs the most, and the hardware prefetcher brings
  the second line.

### The two vector loops on 8-byte windows

`NodeSearchBenchmark`, all slots live, in L1, ns per search. The vector count of the module
`bbtree-vector` calls `trueCount()` on each register.

| Windows | Scalar scan | Vector scan with early exit | Vector count, `trueCount()` per register |
|--:|--:|--:|--:|
| 16 | 7.4 | 8.3 | 4.8 |
| 32 | 9.6 | 8.4 | 7.7 |
| 64 | 12.9 | 11.3 | 14.1 |

A first version of this benchmark repeated the same 256 pairs of node and target. The branch
predictor then learned where each scan stops, and the scalar scan took only 2.6 to 6.2 ns, with
the `trueCount()` count 1.6 to 2.5 times slower. With a random pair each time, as in a real
lookup, the branch-free count wins at 16 and 32 windows. A benchmark of a branchy search must
keep the branch unpredictable.

At 64 windows, the `trueCount()` per register costs more than the branch that it removes. The
Rust loop keeps the counts in a register and adds the lanes once (`WindowWidthBenchmark`).

### The vector count in the map

`GetBenchmark`, 1M keys, `get` in ns, median of 3 rounds that alternate the scalar map and the
map with the vector count of `bbtree-vector`:

| Keys | Scalar | Vector count |
|---|--:|--:|
| `long` | 284 | 273 |
| `uuid4` | 299 | 252 |
| `string` | 387 | 323 |

`getMany` per key was equal within 3% for `long` and `uuid4`, and 9% slower for `string`. A batch
already overlaps the cache misses of 16 lookups, so a mispredicted branch costs less there.

### How often narrow windows tie

`WindowTieAnalysis` builds a map of 1M keys of each type with the default capacities. For each
width, it counts the slots whose window equals the window of a neighbor in the same node. A lookup
that lands on such a slot compares key objects.

| Keys | Level | Ties at 2 bytes | Ties at 4 bytes | Ties at 8 bytes |
|---|---|--:|--:|--:|
| `long` | inner | 0.96% | 0.00% | 0.00% |
| `long` | leaf | 5.98% | 0.00% | 0.00% |
| `uuid4` | inner | 0.97% | 0.00% | 0.00% |
| `uuid4` | leaf | 5.96% | 0.00% | 0.00% |
| `uuid7` | inner | 1.82% | 0.52% | 0.00% |
| `uuid7` | leaf | 14.25% | 0.01% | 0.00% |
| `string` | inner | 99.57% | 22.68% | 0.44% |
| `string` | leaf | 95.29% | 40.72% | 0.13% |

The string keys tie often because the encoding is UTF-16: each ASCII character takes 2 bytes, and
the first byte is 0. Also, the fences of a node can share fewer bytes than its keys.

A tie is not the only cost of a narrow window in a leaf. For `long` keys, an 8-byte window covers
the full key, so equal windows are equal keys, and a hit reads no key object. With a 2-byte or
4-byte window, every hit must read the `Long` to make sure of the match. That read is a cache miss
of its own. The Rust bbtree does not pay it, because its keys are in the node.

## The design that this review recommends

1. **Inner nodes:** a `short[]` of 2-byte windows, or an `int[]` of 4-byte windows, for the
   fixed-length key types (`LONG`, `INTEGER`, `UUID`, `BYTES` of fixed size). Keep `long[]` for
   `STRING`. Choose the width from the `InlineKey`, as the Rust bbtree does (D31, D32, D43).
   - 4 bytes gave 0% ties for `long` and `uuid4` and the best time out of the cache. 2 bytes take
     half the memory, but tie on 1% to 2% of slots, and each tie compares separators.
   - A tie compares the separators with the comparator, as a tie of a non-exact window does now.
2. **Leaves:** keep `long[]` windows. For `long` keys they are exact.
3. **The search loop:** the count of the Rust bbtree. Compare all slots of the capacity, subtract
   the masks into lane counters, and add the lanes once. Use a capacity that is a multiple of the
   lane count.
4. **Invariant:** the slots from `count` to the capacity hold the largest window. Each removal, split
   and merge must write it. Today these slots keep stale windows, which the scalar scan ignores.
5. **Packaging:** the Vector API is still an incubator module in Java 25. Keep the vector count in
   `bbtree-vector`, behind the `WindowScan` interface, and keep a scalar count for the core.
   - A scalar alternative with no incubator module: put 4 windows of 2 bytes in one `long` and
     compare them with SWAR bit operations. I did not measure it.

### Estimated gain

A map of 1M `long` keys has 4 levels: 3 inner levels and the leaves.

- **At 1M keys:** the level above the leaves has approximately 1,000 nodes of approximately 1.1 KB,
  so all inner nodes take approximately 1 MB. They stay in the 16 MB L2 cache, and the cache misses
  of a lookup are in the leaves. Thus the saving is the in-cache difference: approximately 3 ns
  (10.3 to 7.1 ns at a capacity of 64) on each of 3 levels. That is approximately 10 ns of a `get`
  of approximately 200 ns, or 5%.
- **At approximately 100M keys:** the level above the leaves takes approximately 110 MB and does not
  fit in the cache. Each such level then saves approximately 20 ns (41.1 to 21.1 ns out of the
  cache). With 2 such levels, that is approximately 40 ns, or approximately 20%.

This is an estimate from a microbenchmark. The real gain depends on:

- the separator reads on ties,
- the cost to keep the invariant of the tail slots,
- the size of the methods, because the JIT must still inline the search.

Measure it in the map before you keep it.

## The implementation and the measured gain

`VectorBBTreeMaps` makes maps with this design for `LONG`, `INTEGER` and `UUID`:

- Each inner node keeps its 8-byte windows and also an `int[]` of 4-byte windows. The tail slots
  of the `int[]` hold `Integer.MAX_VALUE`, and its length is a multiple of 64.
- The count subtracts the masks into lane counters and adds the lanes one time, as the Rust
  bbtree does. It stops after the register that holds the last live slot.
- Only an inner node with two fences searches the 4-byte windows. The root and the border nodes
  have a prefix of 0, because they have no fence on one side. With 4-byte windows, dense keys
  tied on every separator there: a test of 100,020 lookups of the integers 0 to 99,999 made
  373,950 key compares. With the 8-byte windows at the borders, it makes 0.
- The leaves keep 8-byte windows. Their count also adds the lanes one time.

Measured `get` in ns, 4 forks (the README gives the full table):

| Keys | Size | Scalar | Vector, 8-byte inner windows | Vector, 4-byte inner windows |
|---|--:|--:|--:|--:|
| `long` | 1M | 206 | 167 | 160 |
| `long` | 10M | 312 | 298 | 298 |
| `uuid4` | 1M | 199 | 183 | 171 |
| `uuid4` | 10M | 338 | 306 | 284 |

The estimate in the section "Estimated gain" was approximately 5% for the 4-byte windows at 1M
keys. The measured gain over the 8-byte vector map is 0% to 7% at 1M and 10M keys. The count with
no branch gives the larger part of the gain over the scalar map.

Two results of the implementation differ from the recommendation:

- **The count does not compare the full capacity.** A count over all 64 slots was 10% faster at
  1M keys, but 8% slower at 10M `uuid4` keys. 64 windows of 4 bytes take two 128-byte lines.
  The full count always reads both. The count to the last live slot reads the second line only
  when the node has more than 32 separators.
- **The vector maps make inserts 23% to 33% slower than the scalar map**, with both window
  widths. The cause is not analyzed yet.

## How to run the measurements

```sh
mvn -B package -DskipTests
java -jar bbtree-benchmarks/target/benchmarks.jar WindowWidthBenchmark
java -jar bbtree-benchmarks/target/benchmarks.jar NodeSearchBenchmark
java -Xmx8g -cp bbtree-benchmarks/target/benchmarks.jar io.github.sb.bbtree.WindowTieAnalysis
```
