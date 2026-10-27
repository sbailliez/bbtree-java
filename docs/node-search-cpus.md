# The node search on four CPUs

The vector maps made inserts slower on the M5, and `get` faster. This document gives the cause,
measured with hardware counters, and the result of five node searches on four CPUs.

## Summary

1. **The scalar scan mispredicts and discards speculative work, as expected.** On Graviton3, a
   lookup mispredicts 5.9 branches and discards approximately 650 speculative instructions. A
   binary search with no branch removes 84% of both.
2. **That work does not limit the lookup on three of the four CPUs.** For lookups, the binary
   search with no branch is equal to 47% slower than the scalar scan on Graviton3, Zen 5 and
   Sapphire Rapids. Only the M5 gains: 6% to 20%.
3. **The vector count costs more instructions than it saves.** It executes 185 to 230 more
   instructions per lookup than the scalar scan, because it compares every live window. It is
   slower on Graviton3, Zen 5 and Sapphire Rapids in every mode. On the M5 it is faster only for
   cold absent keys, by 8%.
4. **The 4-byte inner windows cost an extra cache miss.** They are a second array in each inner
   node. On Graviton3 they add 1.5 L2 misses per lookup.
5. **No search is the fastest on all four CPUs.** The scalar scan is the fastest or within 5% for
   inserts on all four, and for lookups on Graviton3 and Sapphire Rapids. The vector scan with an
   early exit is 8% to 9% faster for cold lookups on Zen 5. The binary search is 6% to 20% faster
   for lookups on the M5.
6. **The core map keeps the scalar scan.** A choice of search for each CPU is possible, but this
   port does not make it.

## The machines

| Machine | CPU | Vector registers | Cache line |
|---|---|---|---|
| Apple M5 Pro (laptop) | 6 performance and 12 efficiency cores | NEON, 128 bits | 128 bytes |
| c7g.4xlarge | AWS Graviton3 | SVE, 256 bits | 64 bytes |
| c8a.4xlarge | AMD EPYC 9R45 (Zen 5) | AVX-512 | 64 bytes |
| c7i.4xlarge | Intel Xeon Platinum 8488C (Sapphire Rapids) | AVX-512 | 64 bytes |

All run Corretto 25 with an 8 GB pre-touched heap. The EC2 instances run Amazon Linux 2023, with
`perf` for the hardware counters. The M5 has no counters in this setup.

## The searches

`SearchVariants` gives each search as a map. Each passes 2 million random operations against a
`TreeMap`, with keys that tie on 4 bytes.

| Name | Search |
|---|---|
| `scalar` | The default: a linear scan that stops at the first window that is not smaller. |
| `scan` | A vector scan that stops at the first register with such a window. |
| `count` | The vector count of the Rust bbtree: compare every live window, with no branch. |
| `count-narrow` | `count`, with 4-byte windows in the inner nodes with two fences. |
| `binary` | A binary search where each step moves the base with a conditional select, no branch. |

## The modes

`CounterProbe` builds a map of 1M random `long` keys, and then runs 4 million operations of one
mode. `counter-probe.sh` attaches `perf stat` after the build, so the counts cover the
operations only.

| Mode | Operation |
|---|---|
| `hit-warm` | `containsKey` of present keys, as new `Long` objects in allocation order. The key is in the cache. |
| `hit-cold` | `containsKey` of present keys, as the stored objects in random order. Each lookup starts with a cache miss on the key. |
| `miss-cold` | `containsKey` of absent keys in random order. |
| `insert` | `put` of new keys in allocation order. The map grows from 1M to 5M entries. |

## Results

Time per operation in ns, mean of 2 runs (1 run on Graviton3). The fastest search of each row is
in bold.

**Apple M5 Pro**

| Mode | scalar | scan | count | count-narrow | binary |
|---|--:|--:|--:|--:|--:|
| hit-warm | 105 | 122 (+16%) | 119 (+13%) | 120 (+15%) | **95 (-9%)** |
| hit-cold | 179 | 194 (+8%) | 186 (+4%) | **168 (-6%)** | 168 (-6%) |
| miss-cold | 203 | 194 (-4%) | 187 (-8%) | 194 (-5%) | **163 (-20%)** |
| insert | **271** | 311 (+14%) | 321 (+18%) | 308 (+13%) | 285 (+5%) |

**Graviton3 (c7g)**

| Mode | scalar | scan | count | count-narrow | binary |
|---|--:|--:|--:|--:|--:|
| hit-warm | **251** | 273 (+8%) | 310 (+23%) | 322 (+28%) | 276 (+10%) |
| hit-cold | **356** | 386 (+8%) | 412 (+16%) | 440 (+24%) | 396 (+11%) |
| miss-cold | **357** | 373 (+5%) | 423 (+19%) | 422 (+18%) | 387 (+9%) |
| insert | 524 | 506 (-3%) | 538 (+3%) | 576 (+10%) | **499 (-5%)** |

**AMD Zen 5 (c8a)**

| Mode | scalar | scan | count | count-narrow | binary |
|---|--:|--:|--:|--:|--:|
| hit-warm | **114** | 116 (+1%) | 130 (+14%) | 139 (+22%) | 128 (+11%) |
| hit-cold | 229 | **209 (-9%)** | 237 (+3%) | 245 (+7%) | 228 (-0%) |
| miss-cold | 238 | **218 (-8%)** | 253 (+6%) | 250 (+5%) | 247 (+4%) |
| insert | **403** | 422 (+5%) | 434 (+8%) | 458 (+14%) | 449 (+12%) |

**Intel Sapphire Rapids (c7i)**

| Mode | scalar | scan | count | count-narrow | binary |
|---|--:|--:|--:|--:|--:|
| hit-warm | **316** | 396 (+25%) | 426 (+35%) | 400 (+27%) | 465 (+47%) |
| hit-cold | 430 | **425 (-1%)** | 477 (+11%) | 479 (+11%) | 505 (+18%) |
| miss-cold | **383** | 384 (+0%) | 411 (+7%) | 405 (+6%) | 419 (+9%) |
| insert | **691** | 832 (+20%) | 794 (+15%) | 830 (+20%) | 933 (+35%) |

The differences inside 5% are inside the spread of two runs.

## The counters on Graviton3

Per lookup in the mode `hit-warm`:

| Search | ns | Instructions | Discarded speculative instructions | Mispredicts | L2 misses to memory | Cycles stalled on memory |
|---|--:|--:|--:|--:|--:|--:|
| `scalar` | 251 | 760 | 656 | 5.9 | 3.7 | 204 |
| `scan` | 273 | 745 | 595 | 4.8 | 3.4 | 196 |
| `count` | 310 | 991 | 298 | 4.8 | 3.3 | 212 |
| `count-narrow` | 322 | 1,034 | 235 | 3.0 | 5.2 | 243 |
| `binary` | 276 | 775 | 105 | 1.0 | 3.2 | 219 |

"Discarded speculative instructions" is `inst_spec` minus `instructions`. The binary search
executes almost the same number of instructions as the scalar scan, with 84% less discarded
work, and it is 10% slower. Each step of the binary search waits for the load of the step before
it. The scalar scan loads the windows in order, and the CPU can load them ahead.

## Two layouts that did not help

A lookup level loads the node, then its windows array, then the child reference. Each load needs
the address that the load before it gives. Two prototypes tried to load the windows of a child at
the same time as the child:

- **A second array in each inner node, with the windows array of each child.** L2 misses per
  lookup on Graviton3 went from 3.6 to 7.3, and lookups were 3% faster to 5% slower.
- **Pairs of child and windows in one array.** L2 misses stayed at 6.9 to 9.3, and lookups were
  1% faster to 11% slower.

The counters show part of the cause. With the early load, each mispredicted exit of the scan also
loads the windows of a wrong child. With the binary search, which mispredicts less, the paired
layout had 4.2 L2 misses in place of 7.0, but it was still slower than the current map.

## How to run it

```sh
mvn -B package -DskipTests
java --add-modules=jdk.incubator.vector -cp bbtree-benchmarks/target/benchmarks.jar \
    io.github.sb.bbtree.SearchVariants
bbtree-benchmarks/counter-probe.sh scalar hit-cold
```

`counter-probe.sh` needs Linux, `perf` and `sudo`. On other machines, run `CounterProbe`
directly to get the time only.
