# bbtree-java

[![build](https://github.com/sbailliez/bbtree-java/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sbailliez/bbtree-java/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**bbtree-java is a fast concurrent sorted map for the JVM.** It brings the design of
[bbtree](https://github.com/XiangpengHao/bbtree), the Rust ordered map by
[Xiangpeng Hao](https://xiangpeng.systems/), to Java: a B+ tree with inline keys and optimistic
lock coupling.

This is a hobby port, made for fun with Claude Opus 5.5 (medium effort). The aim was to see how
close Java can get to the Rust original and to its Go port,
[bbtree-go](https://github.com/richardartoul/bbtree-go). The measurements ran on AWS c7g.4xlarge
(Graviton3), c8a.4xlarge (AMD Zen 5) and c7i.4xlarge (Intel Sapphire Rapids), and on an Apple
M5 Pro. All the credit for the design goes to Xiangpeng Hao. The port can certainly be made
faster: [Possible next steps](#possible-next-steps) lists the open ideas.

1. **Close to the Rust original.** On Graviton3, `PackedLongMap` lookups and inserts on one
   thread take at most 6% more time than in the Rust bbtree. With 8 threads, its inserts are 1.4
   to 1.6 times faster than Rust on all three AWS CPUs. Rust stays ahead for lookups on x86 and
   for batch lookups.
2. **Many times faster than the JDK maps.** With 16 threads, `BBTreeMap` does 3.6 to 5.4 times
   the throughput of `ConcurrentSkipListMap`. On one thread, its lookups are 1.8 to 3.6 times
   faster than `TreeMap`, and its scans 9 to 11 times faster (Apple M5).
3. **A drop-in `ConcurrentMap`.** Use `BBTreeMap` where you use `ConcurrentSkipListMap`, or a
   `TreeMap` behind a lock.
4. **Choose your trade-off.** Six kinds of map, from any key type to `long` keys and `long` values
   with no objects at all. `BBTrees` makes each one.
5. **Zero dependencies.** Java 21 or later. The optional Vector API module needs Java 25.
6. **Many tests.** About 3,000 tests: the `ConcurrentMap` contract suites of Guava, differential
   tests against `TreeMap`, and concurrency tests on Arm and x86.

![bbtree-java against the Rust bbtree and the Go port, concurrent insert with 8 threads](docs/assets/race-insert.svg)

![bbtree-java against the Rust bbtree and the Go port, concurrent get with 8 threads](docs/assets/race-get.svg)

The charts: AWS c7g (Graviton3), 1M `uint64` keys, the workloads of bbtree-go, median of 6
rounds. On Zen 5 and Sapphire Rapids, single-thread lookups take 1.3 to 2.8 times the Rust time.
On all three CPUs, batch lookups take 1.7 to 4.0 times the Rust time.
[docs/cross-language.md](docs/cross-language.md) has all the results.

The post [bbtree: fastest ordered map](https://bbtree.xiangpeng.systems/post/) explains the
design.

## Usage

```java
import io.github.sb.bbtree.BBTreeMap;
import io.github.sb.bbtree.InlineKey;

// An InlineKey gives the fast configuration.
BBTreeMap<Long, String> map = new BBTreeMap<>(InlineKey.LONG);
map.put(42L, "answer");
map.get(42L);                                  // "answer"

// Many keys in one call: the lookups go down the tree together.
Long[] keys = {1L, 42L, 7L};
String[] values = new String[keys.length];
map.getMany(keys, values);                     // [null, "answer", null]

// Ranges in key order. The second form stops when the action gives false.
map.forEachInRange(0L, 100L, (k, v) -> System.out.println(k + "=" + v));
map.forEachInRangeWhile(0L, null, (k, v) -> k < 10);

// Without an InlineKey, the map uses the natural order or a Comparator.
BBTreeMap<String, String> reversed = new BBTreeMap<>(Comparator.reverseOrder());
```

For `long` keys, the primitive maps read no key object:

```java
LongBBTreeMap<String> ids = new LongBBTreeMap<>();
ids.put(42, "answer");
ids.get(42);                                   // "answer"
ids.forEachFrom(40, (k, v) -> k < 50);         // a scan that reads no key object
ids.asMap();                                   // the same entries as a BBTreeMap<Long, String>

// long keys and long values. A method that can find no entry takes the value to give back.
PackedLongMap counts = BBTrees.packed();
counts.put(42, 7, -1);                         // -1: there was no old value
counts.get(42, -1);                            // 7
counts.get(43, -1);                            // -1
counts.getMany(new long[] {42, 43}, new long[2], -1);   // [7, -1]
```

The built-in inline keys are `LONG`, `INTEGER`, `UUID`, `STRING`, and `BYTES` (`byte[]` in
unsigned order). For a key type of your own, implement `InlineKey`. Its Javadoc gives the
contract, and `InlineKeyContractTest` gives a check that you can copy.

On Linux, run the JVM with `-XX:+UseTransparentHugePages`. On Graviton3, the 2 MB pages made
lookups 13% faster and inserts 12% to 14% faster.

## Which map to use

The Rust bbtree keeps each key and each value inside its node pages. A Java map keeps references
to key objects, value objects and arrays, and each reference that a lookup follows can be a cache
miss. The kinds of map below remove these objects one step at a time.

| Factory method | Class | Keys | Values | Use it for |
|---|---|---|---|---|
| `BBTrees.naturalOrder()`, `BBTrees.ordered(comparator)` | `BBTreeMap` | any | any | keys with no order-preserving encoding, such as a composite key with a custom `Comparator` |
| `BBTrees.inline(InlineKey)` | `BBTreeMap` | `long`, `int`, `UUID`, `String`, `byte[]`, or your own encoding | any | the general choice: UUID keys, URLs and paths, the `byte[]` keys of a storage engine |
| `BBTrees.longKeys()` | `LongBBTreeMap` | `long` | any | `long` IDs or timestamps that map to objects |
| `BBTrees.longToLong()` | `LongLongBBTreeMap` | `long` | `long` | `long` to `long` maps that need a `Map<Long, Long>` view |
| `BBTrees.packed()` | `PackedLongMap` | `long` | `long` | the fastest `long` to `long` index, with no `Map` view |
| `VectorBBTreeMaps.create(...)` | `BBTreeMap` | as `inline` | any | experiments with the Java Vector API (module bbtree-vector) |

What each step gives, with 1M entries:

- `inline` against `naturalOrder`: `get` of `long` keys took 286 ns against 476 ns (Apple M5).
  Even with no inline keys, `BBTreeMap` was faster than `ConcurrentSkipListMap` (1,910 ns).
- `longToLong` against `longKeys` with `Long` values: hits 23% to 40% faster, batch lookups 18%
  to 31% faster, scans of 64 entries 58% to 67% faster (the three AWS CPUs).
- `packed` against `longToLong`: hits 25% to 48% faster, inserts 22% to 40% faster, scans 15% to
  50% faster, deletes 39% to 55% faster, and batch lookups between 7% slower and 22% faster (the
  three AWS CPUs). `PackedLongMap` has no key, value or node objects: each node is a `long[]`.
- The vector maps were faster than the scalar maps only for cold lookups on the Apple M5. They
  were slower on the three AWS CPUs ([docs/node-search-cpus.md](docs/node-search-cpus.md)).

### Use cases

bbtree fits a sorted map that many threads read and write at the same time, and that needs
ordered access: ranges, the next key, scans in key order. A write locks only the leaf that it
changes, plus the parent when the leaf splits, and a read takes no lock. Examples:

- **An in-memory index with range queries.** Records keyed by a name, a path or a URL, with
  queries such as "all entries from `a` to `b`" or "the 50 entries after this cursor". Request
  threads read while a loader thread writes. Use `BBTrees.inline(InlineKey.STRING)`.
- **A secondary index.** A map from another attribute to the IDs, such as an email to a user ID,
  or a tenant ID with a creation time to an order ID. It finds one key, and it lists the keys of
  one tenant in order. Encode the composite key as a `byte[]` and use
  `BBTrees.inline(InlineKey.BYTES)`.
- **A time-ordered index of events.** Keys that arrive almost in ascending order, such as
  timestamps, sequence numbers or UUID v7, with queries such as "the events after time t". The
  append split keeps the leaves almost full. Use `BBTrees.packed()` for a `long` key and a `long`
  offset, or `BBTrees.inline(InlineKey.UUID)` for UUID keys and event objects.
- **An order book.** The quantity at each price level, with the best price first and the levels
  in price order, while other threads read the book. Use `BBTrees.packed()` with the price as a
  `long` number of ticks. The maps iterate in ascending order only, so negate the bid prices.
- **A map that many threads share.** Any sorted map that one lock protects today, such as sessions
  sorted by expiry time with a cleaner thread that scans them.

A sorted map is not the best choice when:

- the map needs no order: a `ConcurrentHashMap` does not compare keys at all;
- the map is small and only one thread uses it: a `TreeMap` stays in the cache;
- the map removes most of its keys: the maps remove only empty nodes, so a map that shrinks keeps
  sparse nodes.

## How it works

The bbtree post ranks the costs of a lookup: first the cache misses, then the instructions, then
the cache coherence traffic. Each item below decreases one of these costs.

1. **Inline keys.** Each node keeps a `long[]` of 8-byte windows, one for each key: the part of the
   key encoding after the prefix that all keys of the node share. One cache line holds 8 windows.
   A search reads a key object only when two windows are equal. For `Long`, `Integer` and `UUID`,
   equal windows are equal keys, so a lookup reads no key object at all.
2. **Prefixes from the fences.** All keys of a node sort between its two separators in the parent,
   so the node prefix is the common prefix of these separators. No node stores prefix bytes, and
   keys with long shared prefixes, such as URLs, still get useful windows.
3. **A linear scan of the windows.** A search scans the sorted windows and stops at the first one
   that is not smaller than the target. On the four CPUs of the measurements, no other search was
   faster on all of them ([docs/node-search-cpus.md](docs/node-search-cpus.md)).
4. **Optimistic lock coupling.** Each node has a version. A reader reads the version, reads the
   node, and makes sure that the version did not change, so a read writes no shared cache line. A
   writer locks one leaf, plus the parent when a node splits. A full inner node splits on the way
   down, so a split always has room in its parent.
5. **The garbage collector replaces the free list.** On the JVM, a node stays alive while a reader
   holds it, so the map needs no epochs and no free list.
6. **Interleaved batch lookups.** `getMany` moves 16 lookups down the tree one level at a time.
   Java has no prefetch instruction, so each level first starts the cache misses of all lanes
   with plain loads, and then validates the lanes.
7. **Splits without key reads.** When the prefix of a split half does not change, the half
   copies its windows. For `Long`, `Integer` and `UUID`, it shifts them when the prefix grows.
   In both cases, the split reads no key object.
8. **Append splits.** When a key goes after the last key of the rightmost node, the split moves
   only the last key. Ascending keys fill the leaves to 31 of 32 entries, not to half.

## Differences from the Rust bbtree

- **No SIMD in the core.** The Vector API is still an incubator module, so it is in the separate
  module `bbtree-vector`.
- **Only empty nodes are removed.** An empty leaf leaves the tree, and a root with one child gives
  its place to the child. The Rust bbtree also merges nodes that are less than 1/8 full.
- **No single-thread map, no memory-mapped file format, and no write-ahead log.**
- **`size()` comes from a `LongAdder`.** It is exact when no write runs at the same time.

### Possible next steps

A review of the [design documents](https://github.com/XiangpengHao/bbtree/tree/main/design) of
the Rust bbtree found these gaps. The numbers come from those documents, not from this port.

- **Fewer objects for keys other than `long`.** `PackedLongMap` already keeps its nodes in
  arrays with no node objects. A prototype of the same idea for `BBTreeMap`, on the branch
  `layout-rewrite`, made lookups up to 13% faster on Zen 5 and Sapphire Rapids, but batch
  lookups and writes slower. [docs/node-layout.md](docs/node-layout.md) measures the read-only
  prototype that came before it.
- **A prefix for the whole tree (D50).** The root and the border nodes have a prefix of 0, so
  keys with a shared start, such as URLs, tie on every window there. Rust: path lookups from 426
  to 375 ns.
- **Hash tags (D36).** A 1-byte tag of each key would avoid most key reads of a string lookup.
  Rust: path hits 10.7% faster, misses 31.2% faster.
- **1 byte for each ASCII character in `InlineKey.STRING`.** It uses UTF-16, so a window holds
  only 4 characters.
- **An append to the last leaf with no descent (D45).** Rust: 10M ordered `u64` inserts from 31.1
  to 5.3 ns.
- **A batch insert (D53) and an update-or-insert in one descent.** `putAll`, `merge` and `compute`
  use the defaults of `AbstractMap` and `ConcurrentMap`.
- **A reader that waits at a locked leaf**, in place of a new descent from the root.
- **Short separators**, and an end split inside the key space.
- **A test with a controlled thread schedule**, such as Lincheck. The Rust design records 2 races
  that only a controlled scheduler found.

## Tests

`mvn verify` runs approximately 3,000 tests. Most tests use nodes of 4 entries, so that splits and
removals are frequent.

| Test | What it does |
|---|---|
| `InlineKeyContractTest` | Compares each built-in `InlineKey` with a reference byte encoding. |
| `DifferentialTest` | 60,000 random operations for each of 15 configurations against a `TreeMap`, with a check of the tree structure every 2,000 operations. |
| `BBTreeMapTest` | Ranges, iterators, `getMany`, removal of empty nodes, ascending loads, and exceptions from user code, which must leave no lock held. |
| `ConcurrencyTest` | Readers, iterators and batch lookups while writers split nodes and empty whole subtrees. Steady keys must never disappear. |
| `LongBBTreeMapTest`, `LongLongBBTreeMapTest` | The primitive maps against a `TreeMap`, with keys at every prefix length and at `Long.MIN_VALUE` and `Long.MAX_VALUE`. |
| `PackedLongMapTest` | `PackedLongMap` against a `TreeMap`, and lookups of steady keys while writers split and remove leaves. Each test has a time limit, so that a lock defect fails and does not hang. |
| `BBTreesTest` | Each factory method makes a working map. |
| `GuavaMapContractTest` | The `ConcurrentMap` contract suites of guava-testlib. |

## Benchmarks

The benchmarks use JMH:

```sh
mvn -B package -DskipTests
java -jar bbtree-benchmarks/target/benchmarks.jar GetBenchmark
java -jar bbtree-benchmarks/target/benchmarks.jar ConcurrentBenchmark -t 16
```

| Class | What it measures |
|---|---|
| `GetBenchmark` | Lookups, half of them misses, in a map of 1M keys. `getBatch` sends 64 keys to `getMany`. |
| `PutBenchmark` | The build of a map of 1M keys. |
| `ScanBenchmark` | 100 entries after a random key. |
| `ConcurrentBenchmark` | Shared lookups, and 90% lookups with 10% puts. |
| `LongKeyBenchmark` | `long` lookups with boxed and primitive keys. |
| `WindowWidthBenchmark`, `NodeSearchBenchmark` | One node search, scalar and vector. |
| `CrossLanguage` | The workloads of bbtree-go, to compare Java with Go and Rust. |

The parameter `impl` selects the map: `bbtree`, `bbtree-narrow`, `bbtree-vector-scan`,
`bbtree-vector-count`, `bbtree-vector-count-wide`, `bbtree-noinline`, `treemap`, `skiplist`,
`rwlock-treemap`, or `bbtree-<leaf>x<inner>` for other node capacities. The parameter `keyType`
selects the keys: `long`, `dense` (0 to n − 1), `uuid4`, `uuid7` (in time order), and `string`
(URL-like).

### Results on the Apple M5

Apple M5 Pro, Corretto 25.0.4, 8 GB heap, 1M keys. Other builds ran at the same time, at a load
average of 6 to 10, so a cell can be 10% to 50% off. Compare the maps within one table.

Lookups on one thread, half of them misses, in ns. In parentheses: `getMany` with 64 keys a call,
per key.

| Keys | bbtree | bbtree, no inline keys | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|--:|
| `long` | 286 (116) | 476 (288) | 881 | 1,910 |
| `uuid4` | 320 (131) | 496 (284) | 1,142 | 2,023 |
| `uuid7` | 247 (108) | 263 (196) | 444 | 583 |
| `string` | 440 (183) | 815 (712) | 1,239 | 2,545 |

The build of a map of 1M keys on one thread, in ns per insert:

| Keys | bbtree | bbtree, no inline keys | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|--:|
| `long` | 187 | 277 | 601 | 728 |
| `uuid4` | 177 | 493 | 711 | 1,039 |
| `uuid7` | 58 | 39 | 63 | 95 |
| `string` | 289 | 606 | 896 | 1,346 |

A scan of 100 entries on one thread, in ns:

| Keys | bbtree | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|
| `long` | 735 | 6,967 | 6,999 |
| `uuid4` | 707 | 7,546 | 8,269 |
| `string` | 793 | 8,548 | 12,629 |

16 threads that share one map, in million operations per second:

| Keys | Workload | bbtree | ConcurrentSkipListMap | TreeMap with a read-write lock |
|---|---|--:|--:|--:|
| `long` | lookups | 46.5 | 8.9 | 4.4 |
| `long` | 90% lookups, 10% puts | 45.0 | 8.3 | 0.8 |
| `uuid4` | lookups | 42.5 | 9.4 | 4.5 |
| `uuid4` | 90% lookups, 10% puts | 34.1 | 9.5 | 1.0 |
| `string` | lookups | 26.7 | 7.3 | 4.7 |
| `string` | 90% lookups, 10% puts | 29.8 | 6.1 | 0.9 |

## The Vector API variant

The module `bbtree-vector` searches the nodes with the Java Vector API. It needs Java 25 and
`--add-modules jdk.incubator.vector`.

```java
BBTreeMap<Long, String> map =
    VectorBBTreeMaps.create(InlineKey.LONG, VectorBBTreeMaps.Mode.COUNT);
```

- `COUNT` compares every window with no branch on the data, as the Rust bbtree does.
- `SCAN` compares one register at a time and stops at the first window that is not smaller.
- For `LONG`, `INTEGER` and `UUID`, the inner nodes also keep 4-byte windows, so one register
  compares twice as many separators.

On the Apple M5, `COUNT` made `get` 5% to 22% faster than the scalar map, except for `uuid7`
keys, and inserts 23% to 33% slower. It wins when the lookup waits for memory, and loses when the
key and the nodes are in the cache. On Graviton3, Zen 5 and Sapphire Rapids, it was slower than the
scalar map in every mode. Hardware counters show the cause: the count executes 185 to 230 more
instructions per lookup. [docs/vector-apple-silicon.md](docs/vector-apple-silicon.md) and
[docs/node-search-cpus.md](docs/node-search-cpus.md) give the details.

## Java, Go and Rust

[bbtree-go](https://github.com/richardartoul/bbtree-go) runs the same workloads on its Go port
and on the Rust bbtree. `CrossLanguage` runs them on this port, with the same keys, values and
checksums, and the checksums agree on every operation. `cross-language.sh` builds Go and Rust at
pinned revisions and runs the three languages in turn:

```sh
mvn -B package -DskipTests
# Needs internet: clones bbtree-go and the Rust bbtree, and stores the Rust crates locally.
bbtree-benchmarks/cross-language.sh prepare ~/.cache/bbtree-cross
# Works offline: builds Go and Rust, then runs 6 rounds on this machine.
bbtree-benchmarks/cross-language.sh run ~/.cache/bbtree-cross 6 > results.csv
# Runs on another host. It installs Java 25, Go and Rust with dnf if they are missing.
SSH_OPTS="-J bastion" bbtree-benchmarks/cross-language.sh remote ec2-user@10.0.0.5 \
    ~/.cache/bbtree-cross 6 > c7g.csv
# Prints the median of each case.
bbtree-benchmarks/cross-language.sh table results.csv
```

The time of the fastest Java map divided by the time of Rust (less than 1: Java is faster):

| Machine | Inserts and deletes | Lookups and scans |
|---|--:|--:|
| Apple M5 Pro | 0.90 to 2.04 | 1.70 to 5.71 |
| Graviton3 (c7g.4xlarge) | 0.61 to 1.26 | 0.69 to 2.36 |
| AMD Zen 5 (c8a.4xlarge) | 0.73 to 1.27 | 0.66 to 3.51 |
| Intel Sapphire Rapids (c7i.4xlarge) | 0.63 to 1.52 | 0.82 to 4.19 |

The M5 ran before `PackedLongMap` existed. With 8 threads, Java inserts were 3.7 to 5.9 times
faster than Go. [docs/cross-language.md](docs/cross-language.md) gives every case, and the
differences between the three programs.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). bbtree-java is a Java implementation
of the design of [bbtree](https://github.com/XiangpengHao/bbtree) by
[Xiangpeng Hao](https://xiangpeng.systems/), which is licensed under the MIT License or the Apache
License, Version 2.0. [NOTICE](NOTICE) gives the attribution.
