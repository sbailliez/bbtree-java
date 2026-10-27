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
7. **Shifted windows on split.** For `Long`, `Integer`, and `UUID`, a window holds the whole
   rest of the key. A split makes the prefix of each half longer, so the new windows are the old
   windows shifted to the left, and the split reads no key object.
8. **Append splits.** When a key goes after the last key of the rightmost node, the split moves
   only the last key. Ascending keys, such as UUID v7 or sequence numbers, fill the leaves to 31
   of 32 entries, not to half.

## Differences from the Rust bbtree

- No SIMD in the core, because the Vector API is an incubator module. The module `bbtree-vector`
  has a Vector API search, which made `get` 4% to 17% faster on the machine of the measurements
  (see below).
- Removal removes empty nodes only. An empty leaf merges with a sibling, an empty inner node gives
  its child to a sibling (or takes one from a full sibling), and a root with one child gives its
  place to the child. A map that becomes empty goes back to one leaf. The Rust bbtree also
  merges nodes that are less than 1/8 full. This port does not, so a map with few keys that are
  far apart can keep many sparse leaves.
- There is no single-thread variant, no memory-mapped file format, and no write-ahead log.
- `size()` comes from a `LongAdder`. It is exact when no write runs at the same time.

### Possible next steps

These changes were measured or estimated in a review, but they are not done:

- **A node layout with fewer objects.** A lookup level reads the node object, then its `long[]`
  of windows, then its `children` array: three dependent loads, where the Rust page has two.
  Estimated gain: 20% to 30% of `get`. It needs a rewrite without the `Node` classes.
- **Hash tags, a prefix for the whole tree, and 2-byte windows**, as in the Rust bbtree.

## Tests

`mvn verify` runs approximately 3,000 tests:

| Test | What it does |
|---|---|
| `InlineKeyContractTest` | Compares each built-in `InlineKey` with a reference byte encoding: order, windows at each offset, common prefix. |
| `DifferentialTest` | Runs 60,000 random operations for each of 10 configurations against a `TreeMap`. Every 2,000 operations, it checks the tree structure and that no empty leaf remains. |
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

The `impl` values are `bbtree`, `bbtree-vector-scan`, `bbtree-vector-count`, `bbtree-noinline`,
`treemap`, `skiplist`, and `rwlock-treemap`. The key types are `long`, `uuid4` (random UUID), `uuid7` (time-ordered UUID, in time order), and
`string` (URL-like, with a 29-character shared prefix). The `impl` parameter also accepts
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

The module `bbtree-vector` gives node scans that use the Java Vector API
(`jdk.incubator.vector`). It needs Java 25 and `--add-modules jdk.incubator.vector`.

```java
BBTreeMap<Long, String> map =
    VectorBBTreeMaps.create(InlineKey.LONG, VectorBBTreeMaps.Mode.SCAN);
```

It has two modes:

- `COUNT` compares every window and counts, with no branch on the data, as the Rust bbtree does.
- `SCAN` compares one register of windows at a time, and stops at the first window that is not
  smaller than the target.

**Result: on this machine, the vector `COUNT` makes a single `get` 4% to 17% faster. It does not
help `getMany`, and inserts showed no consistent difference in two runs.** The gain does not come
from wide compares: a `LongVector` holds only 2 windows. It comes from the count with no branch.
The scalar scan stops at a random place in each node, and the CPU mispredicts that exit branch
once on each level.

`get` on 1M keys, in ns, median of 3 rounds that alternate the two maps (load average 8 to 20):

| Keys | Scalar | Vector `COUNT` |
|---|--:|--:|
| `long` | 284 | 273 |
| `uuid4` | 299 | 252 |
| `string` | 387 | 323 |

`getMany` per key was equal within 3%, or 9% slower for `string`.

One node search with the windows in the L1 cache, a random node and a random target each time
(`NodeSearchBenchmark`), in ns:

| Windows in the node | Scalar scan | Vector `SCAN` | Vector `COUNT` |
|--:|--:|--:|--:|
| 16 | 7.4 | 8.3 | 4.8 |
| 32 | 9.6 | 8.4 | 7.7 |
| 64 | 12.9 | 11.3 | 14.1 |

The Vector API is an incubator module, so the core keeps the scalar scan. A larger gain needs
narrower windows in inner nodes. [docs/vector-apple-silicon.md](docs/vector-apple-silicon.md)
reviews the best vector design for Apple silicon, with measurements.

On a CPU with 512-bit vectors (AVX-512, 8 `long` lanes), the result can be different. Run
`NodeSearchBenchmark` and `GetBenchmark` with `-p impl=bbtree,bbtree-vector-scan,bbtree-vector-count`
on that machine before you choose.
