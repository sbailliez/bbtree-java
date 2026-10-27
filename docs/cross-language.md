# Java, Go and Rust on four CPUs

The workloads of [bbtree-go](https://github.com/richardartoul/bbtree-go) on its Go port, on the
Rust bbtree, and on this Java port. The three programs use the same flags, keys, values and
checksums. The checksums of all languages and maps agree in every case on all four machines.

## Summary

The ratio is the time of the fastest Java map divided by the time of Rust or Go (less than 1
means that Java is faster):

| Machine | Inserts and deletes, Java / Rust | Lookups and scans, Java / Rust | Java / Go |
|---|--:|--:|--:|
| Apple M5 Pro | 0.90 to 2.04 | 1.70 to 5.71 | 0.38 to 5.58 |
| Graviton3 (c7g.4xlarge) | 0.61 to 1.26 | 0.69 to 2.36 | 0.17 to 3.41 |
| AMD Zen 5 (c8a.4xlarge) | 0.73 to 1.27 | 0.66 to 3.51 | 0.23 to 3.99 |
| Intel Sapphire Rapids (c7i.4xlarge) | 0.63 to 1.52 | 0.82 to 4.19 | 0.25 to 4.50 |

The Apple M5 ran before `PackedLongMap` existed, so its row has the other Java maps only.

- **`PackedLongMap` against Rust, for `uint64` keys:** on Graviton3, hits took 1.05 times the
  Rust time on one thread and 0.82 to 0.89 times in the concurrent map, and inserts 1.06 times on
  one thread and 0.61 times with 8 threads. On Zen 5 and Sapphire Rapids, hits took 1.26 to 2.79
  times the Rust time, and inserts 0.63 to 1.27 times.
- **Deletes:** the tables below ran before `PackedLongMap` removed empty nodes, so its delete
  rows there do less work. With the removal of empty nodes, a later run of 3 rounds gave deletes
  of 0.82 (Graviton3), 1.12 (Zen 5) and 1.81 (Sapphire Rapids) times the Rust time.
- **Batch lookups** stay the largest gap: 1.72 to 3.96 times the Rust time for `uint64` keys.
- **`string` keys** use `BBTreeMap` with inline keys: hits took 1.08 to 1.57 times the Rust time
  on the three EC2 machines, and 90% hits with 10% overwrites on 8 threads took 0.66 to 0.82
  times.
- **Inserts with 8 threads:** Java was faster than Rust on the three EC2 machines for both key
  types (0.61 to 0.87), and 3.7 to 5.9 times faster than Go.

## The machines

| Machine | CPU | Go | Rust | Java | Kernel |
|---|---|---|---|---|---|
| Apple M5 Pro (laptop) | 6 performance and 12 efficiency cores | 1.27.3 | 1.99.1 | Corretto 25.0.4 | macOS |
| c7g.4xlarge | AWS Graviton3, 16 cores | 1.26.10 | 1.99.0 | Corretto 25.0.5 | 6.18 |
| c8a.4xlarge | AMD EPYC 9R45 (Zen 5), 16 vCPUs | 1.26.10 | 1.99.0 | Corretto 25.0.5 | 6.18 |
| c7i.4xlarge | Intel Xeon Platinum 8488C (Sapphire Rapids), 16 vCPUs | 1.26.10 | 1.99.0 | Corretto 25.0.5 | 6.18 |

The EC2 instances run Amazon Linux 2023 in us-east-1. Rust builds with `-C target-cpu=native`,
fat LTO and one codegen unit, as the script of bbtree-go does. Java runs with an 8 GB heap and
4 KB pages, as Go and Rust do, and keeps the last of 5 repetitions in one JVM.

## Results

1,048,576 entries, median of 6 rounds, ns per operation. The raw rows are in
[results/cross-language](results/cross-language).

| Column | Java map |
|---|---|
| Java | `LongLongBBTreeMap` for `uint64`, `BBTreeMap` with `InlineKey.STRING` for `string` |
| Java, vector | the same maps from `VectorBBTreeMaps` |
| Java, packed | `PackedLongMap` (`uint64` only) |

Differences in the workload:

- Java has no single-thread map. In the `single` rows the concurrent maps run on one thread,
  where Rust uses its single-thread map and Go its `Map`.
- The `string` values in Java are `Long` objects. A hit reads the value object, where Go and
  Rust read a `u64` in the leaf. The `uint64` maps keep primitive values.
- The `PackedLongMap` delete rows ran before it removed empty nodes, so they do less work than
  the others. The summary gives the later delete times.

### Apple M5 Pro

| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Java, packed | Best Java / Rust |
|---|---|---|--:|--:|--:|--:|--:|--:|--:|
| `uint64` | single | insert | 1 | 82.0 | 99.1 | 142.2 | 169.5 | - | 1.73× |
| `uint64` | single | hit | 1 | 44.5 | 54.2 | 170.9 | 167.0 | - | 3.75× |
| `uint64` | single | miss | 1 | 34.6 | 35.8 | 115.1 | 109.2 | - | 3.15× |
| `uint64` | single | batch | 1 | 23.0 | 24.2 | 109.5 | 93.7 | - | 4.07× |
| `uint64` | single | delete | 1 | 93.1 | 99.5 | 189.5 | 194.8 | - | 2.04× |
| `uint64` | single | scan64 | 1 | 229.0 | 234.1 | 1307.1 | 1376.1 | - | 5.71× |
| `uint64` | concurrent | insert | 1 | 80.5 | 126.0 | 142.0 | 179.9 | - | 1.76× |
| `uint64` | concurrent | hit | 1 | 46.7 | 69.3 | 167.2 | 164.2 | - | 3.52× |
| `uint64` | concurrent | batch | 1 | 27.7 | 32.1 | 107.0 | 91.5 | - | 3.31× |
| `uint64` | concurrent | insert | 8 | 30.8 | 50.7 | 33.4 | 36.2 | - | 1.09× |
| `uint64` | concurrent | hit | 8 | 8.1 | 12.2 | 28.0 | 25.9 | - | 3.20× |
| `uint64` | concurrent | mixed | 8 | 8.9 | 12.5 | 27.0 | 27.9 | - | 3.02× |
| `string` | single | insert | 1 | 141.0 | 191.0 | 193.1 | 211.6 | - | 1.37× |
| `string` | single | hit | 1 | 154.7 | 268.2 | 351.6 | 346.6 | - | 2.24× |
| `string` | single | miss | 1 | 79.9 | 103.9 | 158.0 | 165.5 | - | 1.98× |
| `string` | single | batch | 1 | 59.4 | 66.1 | 204.1 | 199.5 | - | 3.36× |
| `string` | single | delete | 1 | 211.3 | 193.6 | 230.1 | 265.2 | - | 1.09× |
| `string` | single | scan64 | 1 | 646.2 | 454.6 | 1305.1 | 1350.1 | - | 2.02× |
| `string` | concurrent | insert | 1 | 160.1 | 266.4 | 194.2 | 210.1 | - | 1.21× |
| `string` | concurrent | hit | 1 | 147.5 | 275.7 | 352.5 | 356.9 | - | 2.39× |
| `string` | concurrent | batch | 1 | 71.1 | 90.7 | 194.6 | 198.1 | - | 2.74× |
| `string` | concurrent | insert | 8 | 45.2 | 107.2 | 42.2 | 40.7 | - | 0.90× |
| `string` | concurrent | hit | 8 | 25.7 | 38.9 | 50.0 | 50.0 | - | 1.95× |
| `string` | concurrent | mixed | 8 | 31.1 | 39.9 | 52.9 | 53.1 | - | 1.70× |

### Graviton3 (c7g.4xlarge)

| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Java, packed | Best Java / Rust |
|---|---|---|--:|--:|--:|--:|--:|--:|--:|
| `uint64` | single | insert | 1 | 203.7 | 233.4 | 305.3 | 358.3 | 215.1 | 1.06× |
| `uint64` | single | hit | 1 | 209.1 | 193.6 | 291.5 | 340.4 | 218.9 | 1.05× |
| `uint64` | single | miss | 1 | 156.0 | 124.2 | 251.2 | 292.5 | 201.2 | 1.29× |
| `uint64` | single | batch | 1 | 93.8 | 81.3 | 180.5 | 176.0 | 195.2 | 1.88× |
| `uint64` | single | delete | 1 | 242.8 | 237.7 | 397.5 | 463.8 | 239.8 | 0.99× |
| `uint64` | single | scan64 | 1 | 747.8 | 523.8 | 1115.2 | 1153.2 | 932.1 | 1.25× |
| `uint64` | concurrent | insert | 1 | 228.0 | 294.8 | 307.9 | 352.7 | 217.2 | 0.95× |
| `uint64` | concurrent | hit | 1 | 269.2 | 261.2 | 293.2 | 344.4 | 221.5 | 0.82× |
| `uint64` | concurrent | batch | 1 | 103.6 | 159.0 | 183.0 | 178.0 | 193.7 | 1.72× |
| `uint64` | concurrent | insert | 8 | 57.2 | 163.7 | 44.1 | 50.2 | 34.6 | 0.61× |
| `uint64` | concurrent | hit | 8 | 29.1 | 30.2 | 35.0 | 42.7 | 26.0 | 0.89× |
| `uint64` | concurrent | mixed | 8 | 28.4 | 30.4 | 37.0 | 43.0 | 27.2 | 0.96× |
| `string` | single | insert | 1 | 359.0 | 468.4 | 450.7 | 483.0 | - | 1.26× |
| `string` | single | hit | 1 | 468.8 | 523.9 | 568.6 | 610.7 | - | 1.21× |
| `string` | single | miss | 1 | 275.9 | 284.3 | 353.8 | 405.2 | - | 1.28× |
| `string` | single | batch | 1 | 188.7 | 199.4 | 445.6 | 483.7 | - | 2.36× |
| `string` | single | delete | 1 | 459.9 | 387.2 | 537.3 | 564.3 | - | 1.17× |
| `string` | single | scan64 | 1 | 1385.9 | 894.0 | 3048.1 | 3110.9 | - | 2.20× |
| `string` | concurrent | insert | 1 | 411.6 | 664.9 | 445.4 | 477.2 | - | 1.08× |
| `string` | concurrent | hit | 1 | 484.2 | 547.7 | 566.5 | 606.7 | - | 1.17× |
| `string` | concurrent | batch | 1 | 231.3 | 290.9 | 443.8 | 482.2 | - | 1.92× |
| `string` | concurrent | insert | 8 | 88.5 | 363.9 | 63.7 | 68.8 | - | 0.72× |
| `string` | concurrent | hit | 8 | 60.7 | 65.5 | 70.7 | 76.7 | - | 1.17× |
| `string` | concurrent | mixed | 8 | 105.1 | 65.4 | 72.3 | 77.3 | - | 0.69× |

### AMD Zen 5 (c8a.4xlarge)

| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Java, packed | Best Java / Rust |
|---|---|---|--:|--:|--:|--:|--:|--:|--:|
| `uint64` | single | insert | 1 | 77.9 | 97.9 | 172.7 | 195.1 | 98.6 | 1.27× |
| `uint64` | single | hit | 1 | 46.5 | 43.2 | 174.1 | 196.8 | 87.9 | 1.89× |
| `uint64` | single | miss | 1 | 34.0 | 28.4 | 129.6 | 148.1 | 84.7 | 2.49× |
| `uint64` | single | batch | 1 | 35.1 | 27.6 | 115.5 | 130.8 | 88.9 | 2.53× |
| `uint64` | single | delete | 1 | 99.7 | 95.7 | 252.6 | 270.5 | 101.8 | 1.02× |
| `uint64` | single | scan64 | 1 | 408.3 | 256.0 | 794.3 | 805.9 | 418.4 | 1.02× |
| `uint64` | concurrent | insert | 1 | 95.3 | 121.8 | 174.9 | 192.2 | 99.1 | 1.04× |
| `uint64` | concurrent | hit | 1 | 64.5 | 56.6 | 173.8 | 195.5 | 86.5 | 1.34× |
| `uint64` | concurrent | batch | 1 | 43.1 | 42.5 | 115.4 | 128.8 | 88.8 | 2.06× |
| `uint64` | concurrent | insert | 8 | 46.2 | 123.5 | 43.7 | 54.0 | 33.7 | 0.73× |
| `uint64` | concurrent | hit | 8 | 9.6 | 11.4 | 23.7 | 27.2 | 12.0 | 1.26× |
| `uint64` | concurrent | mixed | 8 | 12.9 | 13.3 | 25.7 | 29.8 | 14.9 | 1.16× |
| `string` | single | insert | 1 | 223.5 | 227.6 | 272.5 | 295.6 | - | 1.22× |
| `string` | single | hit | 1 | 296.3 | 315.6 | 452.6 | 492.1 | - | 1.53× |
| `string` | single | miss | 1 | 109.7 | 82.5 | 198.0 | 219.6 | - | 1.80× |
| `string` | single | batch | 1 | 104.7 | 92.2 | 367.3 | 394.8 | - | 3.51× |
| `string` | single | delete | 1 | 373.5 | 216.6 | 344.2 | 358.8 | - | 0.92× |
| `string` | single | scan64 | 1 | 1047.4 | 718.9 | 2196.8 | 2196.7 | - | 2.10× |
| `string` | concurrent | insert | 1 | 272.1 | 306.0 | 275.8 | 297.3 | - | 1.01× |
| `string` | concurrent | hit | 1 | 410.0 | 326.5 | 461.6 | 485.2 | - | 1.13× |
| `string` | concurrent | batch | 1 | 139.4 | 135.7 | 362.7 | 401.5 | - | 2.60× |
| `string` | concurrent | insert | 8 | 82.5 | 262.3 | 60.9 | 65.2 | - | 0.74× |
| `string` | concurrent | hit | 8 | 54.5 | 42.7 | 59.0 | 63.1 | - | 1.08× |
| `string` | concurrent | mixed | 8 | 93.0 | 44.5 | 61.0 | 64.8 | - | 0.66× |

### Intel Sapphire Rapids (c7i.4xlarge)

| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Java, packed | Best Java / Rust |
|---|---|---|--:|--:|--:|--:|--:|--:|--:|
| `uint64` | single | insert | 1 | 152.4 | 156.0 | 319.4 | 372.7 | 191.4 | 1.26× |
| `uint64` | single | hit | 1 | 74.2 | 78.1 | 271.5 | 304.2 | 206.9 | 2.79× |
| `uint64` | single | miss | 1 | 42.2 | 40.9 | 269.6 | 264.5 | 167.8 | 3.97× |
| `uint64` | single | batch | 1 | 47.1 | 44.4 | 192.9 | 196.0 | 186.4 | 3.96× |
| `uint64` | single | delete | 1 | 145.4 | 142.3 | 426.0 | 397.1 | 199.9 | 1.37× |
| `uint64` | single | scan64 | 1 | 443.6 | 384.0 | 870.5 | 912.7 | 702.1 | 1.58× |
| `uint64` | concurrent | insert | 1 | 169.3 | 197.8 | 303.2 | 324.8 | 182.9 | 1.08× |
| `uint64` | concurrent | hit | 1 | 93.5 | 113.3 | 324.6 | 279.4 | 154.1 | 1.65× |
| `uint64` | concurrent | batch | 1 | 54.9 | 50.6 | 160.2 | 148.7 | 154.6 | 2.71× |
| `uint64` | concurrent | insert | 8 | 60.9 | 144.1 | 54.9 | 60.8 | 38.1 | 0.63× |
| `uint64` | concurrent | hit | 8 | 11.3 | 14.0 | 31.4 | 36.5 | 22.3 | 1.98× |
| `uint64` | concurrent | mixed | 8 | 15.2 | 15.0 | 34.3 | 35.3 | 25.4 | 1.67× |
| `string` | single | insert | 1 | 296.4 | 308.0 | 449.9 | 470.4 | - | 1.52× |
| `string` | single | hit | 1 | 461.4 | 435.4 | 805.4 | 726.4 | - | 1.57× |
| `string` | single | miss | 1 | 140.1 | 144.0 | 388.1 | 438.6 | - | 2.77× |
| `string` | single | batch | 1 | 144.5 | 134.7 | 605.9 | 637.7 | - | 4.19× |
| `string` | single | delete | 1 | 512.6 | 312.8 | 649.5 | 705.5 | - | 1.27× |
| `string` | single | scan64 | 1 | 1327.3 | 859.7 | 2629.9 | 3061.9 | - | 1.98× |
| `string` | concurrent | insert | 1 | 387.3 | 456.8 | 474.7 | 505.7 | - | 1.23× |
| `string` | concurrent | hit | 1 | 551.6 | 516.1 | 751.9 | 899.6 | - | 1.36× |
| `string` | concurrent | batch | 1 | 193.1 | 185.9 | 564.1 | 663.9 | - | 2.92× |
| `string` | concurrent | insert | 8 | 100.4 | 357.7 | 87.8 | 88.7 | - | 0.87× |
| `string` | concurrent | hit | 8 | 64.0 | 60.6 | 85.1 | 96.6 | - | 1.33× |
| `string` | concurrent | mixed | 8 | 111.2 | 60.8 | 90.7 | 94.2 | - | 0.82× |

## How to reproduce

```sh
mvn -B package -DskipTests
bbtree-benchmarks/cross-language.sh prepare ~/.cache/bbtree-cross
# On this machine:
bbtree-benchmarks/cross-language.sh run ~/.cache/bbtree-cross 6 > m5.csv
# On an Amazon Linux 2023 host, through a jump host:
SSH_OPTS="-J bastion" bbtree-benchmarks/cross-language.sh remote ec2-user@10.0.0.5 \
    ~/.cache/bbtree-cross 6 > c7g.csv
bbtree-benchmarks/cross-language.sh table c7g.csv
```

`prepare` needs internet. It clones bbtree-go at commit 262b0b2 and the Rust bbtree at commit
8adae97, and stores the Rust crates locally. `run` and `remote` work offline. `remote` installs
Java 25, Go, Rust and gcc with `dnf` if they are missing, so the host needs only the Amazon Linux
package repository. The three EC2 runs in this document ran at the same time, one on each host.
