# bbtree-java

A concurrent sorted map for Java: a B+ tree with inline keys and optimistic lock coupling.
It is a Java implementation of the design of [bbtree](https://github.com/XiangpengHao/bbtree)
by Xiangpeng Hao. The design is explained in the post
[bbtree: fastest ordered map](https://bbtree.xiangpeng.systems/post/).

`BBTreeMap` implements `java.util.concurrent.ConcurrentMap`. Use it where you use
`ConcurrentSkipListMap`, or a `TreeMap` behind a lock.

## Usage

Java 21 or later is necessary for the core module `bbtree`. It has no runtime dependency. The
optional module `bbtree-vector` needs Java 25.

```java
import io.github.sb.bbtree.BBTreeMap;
import io.github.sb.bbtree.InlineKey;

// Give an InlineKey for the fast configuration.
BBTreeMap<Long, String> map = new BBTreeMap<>(InlineKey.LONG);
map.put(42L, "answer");
map.get(42L);                                  // "answer"

// Look up many keys in one call. The lookups go down the tree together.
Long[] keys = {1L, 42L, 7L};
String[] values = new String[keys.length];
map.getMany(keys, values);                     // [null, "answer", null]

// Read a range in key order.
map.forEachInRange(0L, 100L, (k, v) -> System.out.println(k + "=" + v));

// Without an InlineKey, the map uses the natural order or a Comparator.
BBTreeMap<String, String> reversed = new BBTreeMap<>(Comparator.reverseOrder());
```

For `long` keys, `LongBBTreeMap` takes primitive keys. A lookup with a primitive key reads no
key object, so it saves the cache miss of the boxed `Long`:

```java
LongBBTreeMap<String> ids = new LongBBTreeMap<>();
ids.put(42, "answer");
ids.get(42);                                   // "answer"
ids.getMany(new long[] {1, 42}, new String[2]);
ids.asMap();                                   // the same entries as a BBTreeMap<Long, String>
```

The built-in inline keys are `LONG`, `INTEGER`, `UUID`, `STRING`, and `BYTES` (`byte[]` in
unsigned order). For a key type of your own, implement `InlineKey`. Its Javadoc gives the
contract, and `InlineKeyContractTest` gives a check that you can copy.

## How it works

The post on bbtree ranks the costs of a lookup: first the cache misses, then the
instructions, then the cache coherence traffic. Each item below decreases one of these costs.

1. **Inline keys.** Each node keeps a `long[]` of 8-byte windows, one for each key. A window is
   the part of the order-preserving encoding of the key that starts after the prefix that all
   keys of the node share. One cache line holds 8 windows. The node reads a key object only when
   two windows are equal. For `Long`, `Integer`, and the second half of a `UUID`, equal windows
   mean equal keys, and the lookup reads no key object at all.
2. **Prefixes from the fences.** All keys of a node sort between the two separators of the node
   in its parent. Thus the node prefix is the common prefix of these two separators, and no node
   stores the prefix bytes. Keys with long shared prefixes, such as URLs, still get useful
   windows.
3. **A linear scan of the windows.** A node search scans the sorted windows and stops at the
   first window that is not smaller than the target. The Rust bbtree counts the smaller windows
   with SIMD and no branch. C2 does not vectorize that count on AArch64, and in JMH the scan
   with an early exit was 11% to 22% faster for `get`.
4. **Optimistic lock coupling.** Each node has a version. A reader reads the version, reads the
   node, and makes sure that the version did not change. A read writes no shared cache line. A
   writer locks one leaf, plus the parent when a node splits. A full inner node splits on the way
   down, so a split always has room in its parent.
5. **The garbage collector replaces the free list.** The Rust bbtree keeps freed nodes on a free
   list, because a late reader can still read a freed node. On the JVM, a node stays alive while
   a reader holds it. For the same reason, readers read leaves optimistically too: a value is a
   reference that the garbage collector keeps valid.
6. **Interleaved batch lookups.** `getMany` moves 16 lookups down the tree one level at a time.
   Java has no prefetch instruction, and an acquire load stops later loads. Thus each level
   first searches all lanes and loads each child with a plain load, which starts the cache misses
   together. Then it reads the versions and validates the lanes.
7. **Windows on split without key reads.** If the prefix of a half does not change, the half
   copies its windows. For `Long`, `Integer`, and `UUID`, a window holds the whole rest of the
   key. When a split makes the prefix of a half longer, the new windows are the old windows
   shifted to the left. In both cases the split reads no key object.
8. **Append splits.** When a key goes after the last key of the rightmost node, the split moves
   only the last key. Ascending keys, such as UUID v7 or sequence numbers, fill the leaves to 31
   of 32 entries, not to half.

## Differences from the Rust bbtree

- No SIMD in the core, because the Vector API is an incubator module. The module `bbtree-vector`
  has a Vector API search with 4-byte inner windows. It made `get` 9% to 24% faster than the
  scalar map on the machine of the measurements (see below).
- Removal removes empty nodes only. An empty leaf merges with a sibling, an empty inner node gives
  its child to a sibling (or takes one from a full sibling), and a root with one child gives its
  place to the child. A map that becomes empty goes back to one leaf. The Rust bbtree also
  merges nodes that are less than 1/8 full. This port does not, so a map with few keys that are
  far apart can keep many sparse leaves.
- There is no single-thread variant, no memory-mapped file format, and no write-ahead log.
- `size()` comes from a `LongAdder`. It is exact when no write runs at the same time.

### Possible next steps

A review of the [design documents](https://github.com/XiangpengHao/bbtree/tree/main/design)
of the Rust bbtree found these gaps. The numbers are those of the Rust design documents, not
measurements of this port:

- **A node layout with fewer objects.** A lookup level reads the node object, then its `long[]`
  of windows, then its `children` array: three dependent loads, where the Rust page has two.
  Estimated gain: 20% to 30% of `get`. It needs a rewrite without the `Node` classes.
- **A prefix for the whole tree (D50).** The root and the border nodes have no fence on one side,
  so their prefix is 0. Keys with a shared start, such as URLs, tie on every window there. Rust:
  path lookups 426 to 375 ns.
- **Hash tags (D36).** A 1-byte tag from the hash of the key would avoid most key reads of a
  string hit or miss. Rust: path hits −10.7%, misses −31.2%.
- **A string encoding of 1 byte for each ASCII character.** `InlineKey.STRING` uses UTF-16, so an
  8-byte window holds only 4 characters.
- **An append to the last leaf without a descent (D45).** Rust: 10M ordered `u64` inserts 31.1 to
  5.3 ns.
- **A batch insert (D53)** and an atomic update-or-insert in one descent. `putAll`, `merge` and
  `compute` use the defaults of `AbstractMap` and `ConcurrentMap`.
- **A reader that waits at a locked leaf** in place of a new descent from the root, for leaves
  that many threads write.
- **Short separators**, and an end split that also applies inside the key space.
- **A test with a controlled thread schedule**, such as Lincheck. The Rust design records 2 races
  that only a controlled scheduler found.

## Tests

`mvn verify` runs approximately 3,000 tests:

| Test | What it does |
|---|---|
| `InlineKeyContractTest` | Compares each built-in `InlineKey` with a reference byte encoding: order, windows at each offset, common prefix. |
| `DifferentialTest` | Runs 60,000 random operations for each of 15 configurations against a `TreeMap`, including maps with 4-byte inner windows and keys that tie on 4 bytes. Every 2,000 operations, it checks the tree structure and that no empty leaf remains. |
| `BBTreeMapTest` | Unit tests: ranges, iterators, `getMany`, compaction, ascending loads, and exceptions from user code, which must leave no lock held. |
| `ConcurrencyTest` | Readers, iterators, and batch lookups run while writers split nodes and empty whole subtrees. Steady keys must never disappear. Also atomic `merge` and single removal. |
| `GuavaMapContractTest` | The `ConcurrentMap` contract suites of guava-testlib, for 3 configurations. |

Small nodes (4 entries) in most tests make splits and leaf removals frequent.

## Benchmarks

The benchmarks use JMH. Build them and run one class:

```sh
mvn -B package -DskipTests
java -jar bbtree-benchmarks/target/benchmarks.jar GetBenchmark
java -jar bbtree-benchmarks/target/benchmarks.jar ConcurrentBenchmark -t 16
```

| Class | What it measures |
|---|---|
| `GetBenchmark` | Lookups, half of them misses, in a map of 1M keys. `getBatch` sends 64 keys to `getMany`. |
| `PutBenchmark` | The build of a map of 1M keys, as time per insert. |
| `ScanBenchmark` | 100 entries after a random key. |
| `ConcurrentBenchmark` | Throughput of shared lookups, and of 90% lookups with 10% puts. |
| `LongKeyBenchmark` | Lookups of `long` keys as spread `Long` objects, as boxed `long` values, and with `LongBBTreeMap`. |
| `WindowWidthBenchmark` | One node search over windows of 8, 4 or 2 bytes, scalar and vector, in the cache and out of it. |
| `NodeSearchBenchmark` | One node search with the windows in the L1 cache: the scalar scan and the two vector scans. |
| `CrossLanguage` | The workloads of [bbtree-go](https://github.com/richardartoul/bbtree-go), with the same flags and output, to compare Java with Go and Rust. See below. |

The `impl` values are `bbtree`, `bbtree-narrow` (scalar, with 4-byte inner windows),
`bbtree-vector-scan`, `bbtree-vector-count`, `bbtree-vector-count-wide` (8-byte inner windows),
`bbtree-noinline`, `treemap`, `skiplist`, and `rwlock-treemap`. The key types are `long`,
`dense` (the integers 0 to n − 1), `uuid4` (random UUID), `uuid7` (time-ordered UUID, in time
order), and `string` (URL-like, with a 29-character shared prefix). The `impl` parameter also accepts
`bbtree-<leaf>x<inner>`, for example `bbtree-64x64`, to compare node capacities.

### Results

Apple M5 Pro (6 performance and 12 efficiency cores), Corretto 25.0.4, 8 GB heap, 1M keys. The
machine also ran other builds, at a load average between 6 and 10, and 25 at the end. macOS can
move a benchmark thread to an efficiency core when the machine is busy. Thus the error of a cell
is often 10% to 50%, and two runs can differ by 30%. Compare the maps within one table, and run
the benchmarks on a quiet machine before you depend on a number.

Lookups on one thread, half of them misses, in ns per lookup (less is better). For bbtree, the
number in parentheses is `getMany` with 64 keys a call, per key. For the other maps, a batch is
64 calls to `get`. The lookup keys are `Long`, `UUID` and `String` objects spread in memory.

| Keys | bbtree | bbtree, no inline keys | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|--:|
| `long` | 286 (116) | 476 (288) | 881 | 1,910 |
| `uuid4` | 320 (131) | 496 (284) | 1,142 | 2,023 |
| `uuid7` | 247 (108) | 263 (196) | 444 | 583 |
| `string` | 440 (183) | 815 (712) | 1,239 | 2,545 |

`long` lookups, by the form of the key, in ns per lookup (`LongKeyBenchmark`, 2 forks):

| Key form | bbtree | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|
| `Long` objects spread in memory | 291 | 883 | 1,745 |
| `long` values, boxed for the call | 129 | 780 | 2,671 |
| `long` values with `LongBBTreeMap` | 136 | - | - |

A spread `Long` costs a cache miss before the search starts. A freshly boxed `Long` is in the
cache, so autoboxing is as fast as the primitive API here. `LongBBTreeMap` allocates nothing, and
its batch call takes a `long[]`.

The build of a map of 1M keys on one thread, in ns per insert:

| Keys | bbtree | bbtree, no inline keys | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|--:|
| `long` | 187 | 277 | 601 | 728 |
| `uuid4` | 177 | 493 | 711 | 1,039 |
| `uuid7` | 58 | 39 | 63 | 95 |
| `string` | 289 | 606 | 896 | 1,346 |

A scan of 100 entries on one thread, in ns per scan:

| Keys | bbtree | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|
| `long` | 735 | 6,967 | 6,999 |
| `uuid4` | 707 | 7,546 | 8,269 |
| `string` | 793 | 8,548 | 12,629 |

16 threads that share one map, in million operations per second (more is better):

| Keys | Workload | bbtree | ConcurrentSkipListMap | TreeMap with a read-write lock |
|---|---|--:|--:|--:|
| `long` | lookups | 46.5 | 8.9 | 4.4 |
| `long` | 90% lookups, 10% puts | 45.0 | 8.3 | 0.8 |
| `uuid4` | lookups | 42.5 | 9.4 | 4.5 |
| `uuid4` | 90% lookups, 10% puts | 34.1 | 9.5 | 1.0 |
| `string` | lookups | 26.7 | 7.3 | 4.7 |
| `string` | 90% lookups, 10% puts | 29.8 | 6.1 | 0.9 |

On one thread, the same workloads give bbtree 2.0 to 2.6, the skip list 0.4 to 0.6, and the
locked TreeMap 0.8 to 1.0 million operations per second.

## The Vector API variant

The module `bbtree-vector` gives node searches that use the Java Vector API
(`jdk.incubator.vector`). It needs Java 25 and `--add-modules jdk.incubator.vector`.

```java
BBTreeMap<Long, String> map =
    VectorBBTreeMaps.create(InlineKey.LONG, VectorBBTreeMaps.Mode.COUNT);
LongBBTreeMap<String> longs = VectorBBTreeMaps.createLong(VectorBBTreeMaps.Mode.COUNT);
```

- `COUNT` compares every window and counts, with no branch on the data, as the Rust bbtree does.
  It subtracts each compare mask from lane counters and adds the lanes one time at the end.
- `SCAN` compares one register of windows at a time, and stops at the first window that is not
  smaller than the target.

For key types of a fixed length (`LONG`, `INTEGER`, `UUID`), the inner nodes of a vector map
also keep the first 4 bytes of each window, in an `int[]`. A 128-bit NEON register then compares
4 separators in place of 2. The slots after the live ones hold `Integer.MAX_VALUE`, and the
array length is a multiple of 64, so the count reads whole registers with no mask. Only the
inner nodes with two fences search the 4-byte windows. The root and the border nodes have a
prefix of 0, so dense keys, such as the integers 0 to n − 1, would tie on 4 bytes there. These
nodes search the 8-byte windows. A tie on 4 bytes compares the separators.

**Result: the vector `COUNT` with 4-byte inner windows makes `get` 5% to 22% faster than the
scalar map, except for time-ordered `uuid7` keys. It makes inserts 23% to 33% slower. The
vector count is faster when the key object is not in the cache, and slower when it is.**

`GetBenchmark` and `PutBenchmark`, 4 forks of 3 iterations each, on a quiet machine. Time per
operation in ns:

| Keys | Size | Scalar | Vector, 8-byte inner windows | Vector, 4-byte inner windows |
|---|--:|--:|--:|--:|
| `get` `long` | 1M | 206 ± 21 | 167 ± 7 | 160 ± 1 |
| `get` `long` | 10M | 312 ± 6 | 298 ± 8 | 298 ± 17 |
| `get` `dense` | 1M | 171 ± 2 | 159 ± 2 | 151 ± 1 |
| `get` `dense` | 10M | 306 ± 11 | 296 ± 9 | 285 ± 3 |
| `get` `uuid4` | 1M | 199 ± 8 | 183 ± 3 | 171 ± 2 |
| `get` `uuid4` | 10M | 338 ± 27 | 306 ± 4 | 284 ± 1 |
| `get` `uuid7` | 1M | 165 ± 11 | 164 ± 5 | 165 ± 11 |
| `get` `uuid7` | 10M | 235 ± 2 | 238 ± 15 | 233 ± 1 |
| `getMany` `long` | 1M | 84 ± 1 | 81 ± 1 | 87 ± 17 |
| `getMany` `long` | 10M | 126 ± 2 | 121 ± 4 | 132 ± 2 |
| `getMany` `uuid4` | 1M | 105 ± 6 | 96 ± 1 | 89 ± 1 |
| `getMany` `uuid4` | 10M | 139 ± 3 | 136 ± 5 | 138 ± 5 |
| insert `long` | 1M | 117 ± 3 | 144 ± 4 | 145 ± 3 |
| insert `uuid4` | 1M | 117 ± 3 | 148 ± 3 | 144 ± 3 |
| insert `uuid7` | 1M | 50 ± 1 | 64 ± 0 | 66 ± 1 |

- The 4-byte windows give `get` 0% to 7% over the 8-byte vector map. The rest of the gain comes
  from the count with no branch: the scalar scan stops at a random place in each node, and the
  CPU mispredicts that exit branch once on each level.
- With the scalar scan, an earlier run gave 4-byte windows no gain for `get`, and `getMany` of
  `long` keys was 13% to 16% slower. Thus the core map keeps 8-byte windows.
- A count over the full capacity, with no loop bound from the count of the node, was 10% faster
  at 1M keys and 8% slower at 10M `uuid4` keys. It reads the second cache line of the windows
  every time. The count stops at the last live register.
- The insert cost is the same with both window widths, so it comes from the vector count. The
  cache state of the key decides whether the count is faster than the scalar scan. On a map of 1M
  `long` keys, with `containsKey`:

  | Lookup keys | Scalar, ns | Vector, ns |
  |---|--:|--:|
  | In allocation order, so in the cache | 108 to 118 | 112 to 118 |
  | Spread in memory, so each lookup starts with a cache miss on the key | 182 to 187 | 148 to 165 |

  `GetBenchmark` looks up spread keys. `PutBenchmark` inserts the keys in allocation order. On
  maps that fit in the cache (10,000 and 100,000 keys), the vector `get` was 13 ns slower. Thus
  the vector count wins when the lookup waits for memory, and the scalar scan wins when the key
  and the nodes are in the cache. A probable reason is that the scalar scan branches on each
  compare: when the key arrives late, the CPU runs ahead on predicted branches and throws away
  that work. The count has no such branch. This reason is not proven with hardware counters.
  The insert path has no other cause: the vector maps allocate the same 38.5 bytes per insert,
  and larger C2 inlining limits do not change the times.

[docs/vector-apple-silicon.md](docs/vector-apple-silicon.md) gives the review of the vector design
for Apple silicon, with the node search measurements. On a CPU with 512-bit vectors (AVX-512), the
result can be different. Run `GetBenchmark` with
`-p impl=bbtree,bbtree-vector-count-wide,bbtree-vector-count` on that machine before you choose.

## Java, Go and Rust

[bbtree-go](https://github.com/richardartoul/bbtree-go) is a Go port of the Rust bbtree, with
NEON assembly on arm64. Its `bench/main.go` and `bench/reference.rs` run the same workloads on Go
and on Rust. `CrossLanguage` runs them on this port, with the same flags, keys, values and
checksums. The checksums of the three agree on every operation. `cross-language.sh` builds the Go
and Rust programs at pinned revisions and interleaves the three languages:

```sh
mvn -B package -DskipTests
bbtree-benchmarks/cross-language.sh 6 > results.csv
```

Apple M5 Pro, Go 1.27.3, Rust 1.99.1 with `-C target-cpu=native`, Corretto 25.0.4 with an 8 GB
heap. 1,048,576 entries, median of 6 rounds, ns per operation. Java runs the workload 5 times in
one JVM and keeps the last time. "Java" is the scalar map (`LongBBTreeMap` for `uint64`), and
"Java, vector" is the map of `bbtree-vector`.

| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Best Java / Rust |
|---|---|---|--:|--:|--:|--:|--:|--:|
| `uint64` | single | insert | 1 | 76.4 | 91.0 | 118.9 | 143.5 | 1.56× |
| `uint64` | single | hit | 1 | 36.7 | 42.8 | 156.0 | 154.7 | 4.21× |
| `uint64` | single | miss | 1 | 32.1 | 33.8 | 96.9 | 99.9 | 3.02× |
| `uint64` | single | batch hit | 1 | 22.4 | 22.7 | 102.7 | 87.1 | 3.89× |
| `uint64` | single | delete | 1 | 84.9 | 90.5 | 150.4 | 176.7 | 1.77× |
| `uint64` | single | scan of 64 | 1 | 204.4 | 198.6 | 1169.5 | 1246.2 | 5.72× |
| `uint64` | concurrent | insert | 1 | 75.3 | 105.3 | 119.3 | 143.4 | 1.58× |
| `uint64` | concurrent | hit | 1 | 37.6 | 58.0 | 158.6 | 152.2 | 4.05× |
| `uint64` | concurrent | batch hit | 1 | 27.0 | 30.1 | 104.4 | 88.4 | 3.27× |
| `uint64` | concurrent | insert | 8 | 29.0 | 50.0 | 29.6 | 33.0 | 1.02× |
| `uint64` | concurrent | hit | 8 | 7.9 | 12.2 | 24.7 | 24.2 | 3.08× |
| `uint64` | concurrent | 90% hits, 10% overwrites | 8 | 8.8 | 12.6 | 25.8 | 26.5 | 2.92× |
| `string` | single | insert | 1 | 136.4 | 177.3 | 177.0 | 199.1 | 1.30× |
| `string` | single | hit | 1 | 158.8 | 250.4 | 328.9 | 332.6 | 2.07× |
| `string` | single | miss | 1 | 71.0 | 97.6 | 136.9 | 147.8 | 1.93× |
| `string` | single | batch hit | 1 | 56.0 | 58.8 | 192.7 | 193.7 | 3.44× |
| `string` | single | delete | 1 | 188.3 | 173.4 | 213.2 | 226.3 | 1.13× |
| `string` | single | scan of 64 | 1 | 585.5 | 398.6 | 1150.9 | 1191.1 | 1.97× |
| `string` | concurrent | insert | 1 | 140.3 | 243.8 | 176.6 | 194.4 | 1.26× |
| `string` | concurrent | hit | 1 | 136.7 | 249.4 | 321.4 | 319.1 | 2.33× |
| `string` | concurrent | batch hit | 1 | 63.5 | 78.6 | 191.5 | 193.4 | 3.01× |
| `string` | concurrent | insert | 8 | 43.5 | 104.3 | 37.0 | 40.0 | 0.85× |
| `string` | concurrent | hit | 8 | 24.1 | 35.9 | 47.6 | 47.9 | 1.97× |
| `string` | concurrent | 90% hits, 10% overwrites | 8 | 31.1 | 36.6 | 48.1 | 49.0 | 1.55× |

Differences in the workload:

- Java has one map type, which is concurrent. In the `single` rows it runs on one thread, where
  Rust uses its single-thread map and Go its `Map`.
- Java values are `Long` objects. A hit reads the value object, where Go and Rust read a `u64`
  in the leaf.
- The Java scan uses the entry iterator, which makes one entry object for each entry.

Java is close to Rust for inserts and deletes: 0.85 to 1.8 times the Rust time. With 8 threads,
Java inserts are faster than Go, and `string` inserts are also faster than Rust. Lookups and
scans take 1.6 to 5.7 times the Rust time. A large part of the gap comes from cache misses.
At 65,536 entries, which stay in the cache, a `uint64` hit took 50 ns in Java and 21 ns in Rust.
At 1M entries, it took 168 ns and 39 ns. The larger map thus added 118 ns in Java and 18 ns in
Rust. A Java lookup reads more separate objects on each level: the node, its windows array and
its children array, and at the end the value object. The node layout with fewer objects in
"Possible next steps" addresses this.
