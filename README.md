# bbtree-java

A concurrent sorted map for Java: a B+ tree with inline keys and optimistic lock coupling.
It is a Java implementation of the design of [bbtree](https://github.com/XiangpengHao/bbtree)
by Xiangpeng Hao. The design is explained in the post
[bbtree: fastest ordered map](https://bbtree.xiangpeng.systems/post/).

`BBTreeMap` implements `java.util.concurrent.ConcurrentMap`. Use it where you use
`ConcurrentSkipListMap`, or a `TreeMap` behind a lock.

## Usage

Java 21 or later is necessary. The library has no runtime dependency.

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
3. **A search with no branch.** A node search counts the windows that are smaller than the window
   of the target. The count is the index of the child. The loop has no data-dependent branch, so
   the CPU does not mispredict.
4. **Optimistic lock coupling.** Each node has a version. A reader reads the version, reads the
   node, and makes sure that the version did not change. A read writes no shared cache line. A
   writer locks one leaf, plus the parent when a node splits. A full inner node splits on the way
   down, so a split always has room in its parent.
5. **The garbage collector replaces the free list.** The Rust bbtree keeps freed nodes on a free
   list, because a late reader can still read a freed node. On the JVM, a node stays alive while
   a reader holds it. For the same reason, readers read leaves optimistically too: a value is a
   reference that the garbage collector keeps valid.
6. **Interleaved batch lookups.** `getMany` moves 16 lookups down the tree one level at a time.
   The searches of different lookups do not depend on each other, so the CPU waits for their
   cache misses in parallel.
7. **Append splits.** When a key goes after the last key of the rightmost node, the split moves
   only the last key. Ascending keys, such as UUID v7 or sequence numbers, fill the leaves to 31
   of 32 entries, not to half.

## Differences from the Rust bbtree

- No hand-written SIMD. The JIT compiles the count loop. The Java Vector API is still an
  incubator module in Java 25.
- Removal unlinks a leaf that becomes empty, if the leaf has a left sibling in the same parent.
  The map does not merge leaves that are not empty, and the tree height does not decrease.
- There is no single-thread variant, no memory-mapped file format, and no write-ahead log.
- `size()` comes from a `LongAdder`. It is exact when no write runs at the same time.

## Tests

`mvn verify` runs approximately 3000 tests:

| Test | What it does |
|---|---|
| `InlineKeyContractTest` | Compares each built-in `InlineKey` with a reference byte encoding: order, windows at each offset, common prefix. |
| `DifferentialTest` | Runs 60,000 random operations for each of 10 configurations against a `TreeMap`, and checks the tree structure every 2,000 operations. |
| `BBTreeMapTest` | Unit tests: ranges, iterators, `getMany`, leaf removal, ascending loads, errors. |
| `ConcurrencyTest` | Readers, iterators, and batch lookups run while writers split and remove leaves. Steady keys must never disappear. Also atomic `merge` and single removal. |
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

The key types are `long`, `uuid4` (random UUID), `uuid7` (time-ordered UUID, in time order), and
`string` (URL-like, with a 29-character shared prefix). The `impl` parameter also accepts
`bbtree-<leaf>x<inner>`, for example `bbtree-64x64`, to compare node capacities.

### Results

Apple M5 Pro (18 cores), Corretto 25.0.4, 8 GB heap, 1M keys, 1 JMH fork. The machine also ran
a browser and other JVMs, so the error of each cell is between 10% and 100%. Run the benchmarks
on a quiet machine before you depend on a number.

Lookups on one thread, half of them misses, in ns per lookup (less is better):

| Keys | bbtree | bbtree, no inline keys | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|--:|
| `long` | 365 | 456 | 1,239 | 1,996 |
| `uuid4` | 346 | 474 | 1,553 | 2,051 |
| `uuid7` | 292 | 285 | 733 | 629 |
| `string` | 475 | 1,021 | 1,248 | 3,293 |

The build of a map of 1M keys on one thread, in ns per insert:

| Keys | bbtree | bbtree, no inline keys | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|--:|
| `long` | 174 | 268 | 518 | 1,515 |
| `uuid4` | 189 | 358 | 708 | 1,598 |
| `uuid7` | 57 | 46 | 63 | 117 |
| `string` | 304 | 523 | 1,006 | 2,333 |

A scan of 100 entries on one thread, in ns per scan:

| Keys | bbtree | TreeMap | ConcurrentSkipListMap |
|---|--:|--:|--:|
| `long` | 1,004 | 6,648 | 7,346 |
| `uuid4` | 810 | 8,734 | 6,426 |
| `string` | 797 | 10,520 | 8,285 |

16 threads that share one map, in million operations per second (more is better):

| Keys | Workload | bbtree | ConcurrentSkipListMap | TreeMap with a read-write lock |
|---|---|--:|--:|--:|
| `long` | lookups | 36.5 | 8.9 | 4.4 |
| `long` | 90% lookups, 10% puts | 33.6 | 10.7 | 0.8 |
| `uuid4` | lookups | 33.9 | 10.4 | 5.1 |
| `uuid4` | 90% lookups, 10% puts | 32.9 | 8.2 | 1.3 |
| `string` | lookups | 26.7 | 6.4 | 4.9 |
| `string` | 90% lookups, 10% puts | 22.9 | 6.3 | 0.8 |

On one thread, the same workload gives bbtree 2.3 to 3.0, the skip list 0.5 to 0.6, and the
locked TreeMap 0.9 to 1.3 million operations per second.

`getMany` gave between 0.9 and 2.3 times the speed of `get` in different runs on this machine.
The noise is too high to give one number.
