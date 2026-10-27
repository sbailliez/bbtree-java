# A node layout with fewer objects

A lookup level in this port loads the child reference, then the child object, then its windows
array. Each load needs the address that the load before it gives. This document measures a layout
with one object fewer per level, before a rewrite of the map.

## The layouts

| Layout | One node | Loads that wait for each other on one level |
|---|---|---|
| Current | A `Node` object with the version, the count and the prefix, and references to the windows, the keys, and the children or values | the child reference, the child object, the windows |
| New | A `long[]` head (slot 0 the version, slot 1 the count and the prefix, then the windows), and a body: for an inner node an `Object[]` of pairs (child head, child body), for a leaf the values | the pair of the child, the child head |

In the new layout the version shares a cache line with the first windows, and the version is
read with acquire loads on the array element.

## The prototype

`FlatLayoutProbe` builds a `LongLongBBTreeMap` of 1M random keys, and then makes two copies of it:

- the same nodes in the current layout, copied in depth-first order;
- the nodes in the new layout, copied in the same order.

All three do lookups of the same keys, with the same linear scan and the same version checks.
The copies give the same value as the map for every 997th key. The prototype has no writers.

| Mode | Lookups |
|---|---|
| `hit-cold` | Present keys in random order |
| `miss-cold` | Absent keys in random order |
| `hit-warm` | Present keys in sorted order, so that consecutive lookups share nodes in the cache |

## Results

c7g.4xlarge (Graviton3), c8a.4xlarge (Zen 5) and c7i.4xlarge (Sapphire Rapids), with
`-XX:+UseTransparentHugePages`. Median of 5 timings of 4M lookups, ns per lookup, two runs.

| Instance | Mode | Current map | Current layout, copied | New layout | New against copied |
|---|---|--:|--:|--:|--:|
| Graviton3 | `hit-cold` | 241 | 230 | 200 to 204 | −12% to −13% |
| Graviton3 | `miss-cold` | 205 | 192 to 195 | 173 to 177 | −10% |
| Graviton3 | `hit-warm` | 63 | 54 | 57 | +5% |
| Zen 5 | `hit-cold` | 154 to 156 | 97 | 79 to 80 | −17% to −18% |
| Zen 5 | `miss-cold` | 106 to 108 | 92 to 94 | 80 | −13% to −15% |
| Zen 5 | `hit-warm` | 34 | 27 to 29 | 27 | −7% to 0% |
| Sapphire Rapids | `hit-cold` | 313 to 413 | 208 to 290 | 164 to 197 | −21% to −32% |
| Sapphire Rapids | `miss-cold` | 163 to 267 | 166 to 198 | 140 to 162 | −16% to −18% |
| Sapphire Rapids | `hit-warm` | 51 to 52 | 39 to 40 | 42 to 43 | +8% |

Two effects:

1. **The layout.** With the nodes in the same memory order, the new layout makes cold lookups 10%
   to 21% faster (32% in one Sapphire Rapids run, which varied more). Warm lookups are 5% to 8%
   slower on Graviton3 and Sapphire Rapids, and equal or faster on Zen 5.
2. **The memory order of the nodes.** The same layout, copied in depth-first order, made cold hits
   4% (Graviton3) to 38% (Zen 5) faster than the map that 1M random inserts built. This needs no
   change of layout. A map that random inserts build allocates each new node at the time of its
   split, between the objects that the program allocates at the same time.

The prototype does not measure the cost of the new layout for writes. A split or a merge then
changes two arrays and the pair in the parent, in place of one node object.

## How to run it

```sh
mvn -B package -DskipTests
java -Xms12g -Xmx12g -XX:+AlwaysPreTouch -XX:+UseTransparentHugePages \
    -cp bbtree-benchmarks/target/benchmarks.jar io.github.sb.bbtree.FlatLayoutProbe hit-cold
```
