# Java, Go and Rust on four CPUs

The workloads of [bbtree-go](https://github.com/richardartoul/bbtree-go) on its Go port, on the
Rust bbtree, and on this Java port. The three programs use the same flags, keys, values and
checksums. The checksums of the three agree in every case on all four machines.

## Summary

The ratio is the time of the faster Java map divided by the time of Rust (less than 1 means that
Java is faster):

| Machine | Inserts and deletes | Lookups and scans | Java against Go |
|---|--:|--:|--:|
| Apple M5 Pro | 0.90 to 2.04 | 1.70 to 5.71 | 0.38 to 5.58 |
| Graviton3 (c7g.4xlarge) | 0.72 to 1.73 | 0.84 to 3.65 | 0.18 to 5.32 |
| AMD Zen 5 (c8a.4xlarge) | 0.77 to 2.53 | 0.67 to 5.98 | 0.25 to 9.17 |
| Intel Sapphire Rapids (c7i.4xlarge) | 0.91 to 3.04 | 0.87 to 6.82 | 0.24 to 7.37 |

- **Inserts with 8 threads:** Java is at most 9% slower than Rust on all four machines, and
  faster on six of the eight rows (0.72 to 1.09). It is 1.5 to 5.6 times faster than Go.
- **Lookups of `uint64` keys:** Java is 1.6 to 6.8 times slower than Rust. The gap is smallest on
  Graviton3, where the Rust lookups are also slow: a `uint64` hit takes 198 ns, against 45 ns on
  the M5.
- **`string` keys:** Java is within 1.1 to 1.6 times of Rust for hits on the three EC2 machines,
  and faster than Rust for 90% hits with 10% overwrites on 8 threads (0.67 to 0.87).
- **The vector map** of `bbtree-vector` is slower than the scalar Java map on the three EC2
  machines: the median ratio is 1.09. On the M5 the median ratio is 1.02.

## The machines

| Machine | CPU | Go | Rust | Java | Kernel |
|---|---|---|---|---|---|
| Apple M5 Pro (laptop) | 6 performance and 12 efficiency cores | 1.27.3 | 1.99.1 | Corretto 25.0.4 | macOS |
| c7g.4xlarge | AWS Graviton3, 16 cores | 1.26.10 | 1.99.0 | Corretto 25.0.5 | 6.18 |
| c8a.4xlarge | AMD EPYC 9R45 (Zen 5), 16 vCPUs | 1.26.10 | 1.99.0 | Corretto 25.0.5 | 6.18 |
| c7i.4xlarge | Intel Xeon Platinum 8488C (Sapphire Rapids), 16 vCPUs | 1.26.10 | 1.99.0 | Corretto 25.0.5 | 6.18 |

The EC2 instances run Amazon Linux 2023 in us-east-1. Rust builds with `-C target-cpu=native`,
fat LTO and one codegen unit, as the script of bbtree-go does. Java runs with an 8 GB heap and
keeps the last of 5 repetitions in one JVM.

## Results

1,048,576 entries, median of 6 rounds, ns per operation. "Java" is the scalar map
(`LongBBTreeMap` for `uint64`), and "Java, vector" is the map of `bbtree-vector`. The raw rows are
in [results/cross-language](results/cross-language).

Differences in the workload:

- Java has one map type, which is concurrent. In the `single` rows it runs on one thread, where
  Rust uses its single-thread map and Go its `Map`.
- Java values are `Long` objects. A hit reads the value object, where Go and Rust read a `u64`
  in the leaf.
- The Java scan uses the entry iterator, which makes one entry object for each entry.

### Apple M5 Pro

| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Best Java / Rust |
|---|---|---|--:|--:|--:|--:|--:|--:|
| `uint64` | single | insert | 1 | 82.0 | 99.1 | 142.2 | 169.5 | 1.73× |
| `uint64` | single | hit | 1 | 44.5 | 54.2 | 170.9 | 167.0 | 3.75× |
| `uint64` | single | miss | 1 | 34.6 | 35.8 | 115.1 | 109.2 | 3.15× |
| `uint64` | single | batch | 1 | 23.0 | 24.2 | 109.5 | 93.7 | 4.07× |
| `uint64` | single | delete | 1 | 93.1 | 99.5 | 189.5 | 194.8 | 2.04× |
| `uint64` | single | scan64 | 1 | 229.0 | 234.1 | 1307.1 | 1376.1 | 5.71× |
| `uint64` | concurrent | insert | 1 | 80.5 | 126.0 | 142.0 | 179.9 | 1.76× |
| `uint64` | concurrent | hit | 1 | 46.7 | 69.3 | 167.2 | 164.2 | 3.52× |
| `uint64` | concurrent | batch | 1 | 27.7 | 32.1 | 107.0 | 91.5 | 3.31× |
| `uint64` | concurrent | insert | 8 | 30.8 | 50.7 | 33.4 | 36.2 | 1.09× |
| `uint64` | concurrent | hit | 8 | 8.1 | 12.2 | 28.0 | 25.9 | 3.20× |
| `uint64` | concurrent | mixed | 8 | 8.9 | 12.5 | 27.0 | 27.9 | 3.02× |
| `string` | single | insert | 1 | 141.0 | 191.0 | 193.1 | 211.6 | 1.37× |
| `string` | single | hit | 1 | 154.7 | 268.2 | 351.6 | 346.6 | 2.24× |
| `string` | single | miss | 1 | 79.9 | 103.9 | 158.0 | 165.5 | 1.98× |
| `string` | single | batch | 1 | 59.4 | 66.1 | 204.1 | 199.5 | 3.36× |
| `string` | single | delete | 1 | 211.3 | 193.6 | 230.1 | 265.2 | 1.09× |
| `string` | single | scan64 | 1 | 646.2 | 454.6 | 1305.1 | 1350.1 | 2.02× |
| `string` | concurrent | insert | 1 | 160.1 | 266.4 | 194.2 | 210.1 | 1.21× |
| `string` | concurrent | hit | 1 | 147.5 | 275.7 | 352.5 | 356.9 | 2.39× |
| `string` | concurrent | batch | 1 | 71.1 | 90.7 | 194.6 | 198.1 | 2.74× |
| `string` | concurrent | insert | 8 | 45.2 | 107.2 | 42.2 | 40.7 | 0.90× |
| `string` | concurrent | hit | 8 | 25.7 | 38.9 | 50.0 | 50.0 | 1.95× |
| `string` | concurrent | mixed | 8 | 31.1 | 39.9 | 52.9 | 53.1 | 1.70× |

### Graviton3 (c7g.4xlarge)

| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Best Java / Rust |
|---|---|---|--:|--:|--:|--:|--:|--:|
| `uint64` | single | insert | 1 | 197.8 | 228.1 | 316.4 | 368.1 | 1.60× |
| `uint64` | single | hit | 1 | 198.2 | 184.4 | 404.4 | 444.2 | 2.04× |
| `uint64` | single | miss | 1 | 150.3 | 120.4 | 255.7 | 301.3 | 1.70× |
| `uint64` | single | batch | 1 | 92.2 | 80.2 | 237.3 | 238.3 | 2.57× |
| `uint64` | single | delete | 1 | 234.9 | 230.6 | 405.2 | 469.4 | 1.73× |
| `uint64` | single | scan64 | 1 | 736.1 | 504.7 | 2686.3 | 2769.6 | 3.65× |
| `uint64` | concurrent | insert | 1 | 219.0 | 288.4 | 317.5 | 380.3 | 1.45× |
| `uint64` | concurrent | hit | 1 | 262.8 | 247.4 | 411.1 | 453.5 | 1.56× |
| `uint64` | concurrent | batch | 1 | 100.4 | 152.8 | 239.1 | 238.3 | 2.37× |
| `uint64` | concurrent | insert | 8 | 58.0 | 165.2 | 45.2 | 51.5 | 0.78× |
| `uint64` | concurrent | hit | 8 | 28.1 | 29.5 | 48.9 | 55.3 | 1.74× |
| `uint64` | concurrent | mixed | 8 | 28.2 | 30.2 | 51.7 | 56.6 | 1.83× |
| `string` | single | insert | 1 | 350.7 | 456.2 | 447.0 | 481.0 | 1.27× |
| `string` | single | hit | 1 | 458.8 | 509.6 | 558.3 | 612.4 | 1.22× |
| `string` | single | miss | 1 | 270.8 | 280.3 | 347.7 | 395.3 | 1.28× |
| `string` | single | batch | 1 | 186.4 | 194.9 | 446.4 | 482.2 | 2.39× |
| `string` | single | delete | 1 | 446.1 | 379.2 | 520.5 | 561.8 | 1.17× |
| `string` | single | scan64 | 1 | 1354.4 | 872.4 | 2881.0 | 2958.0 | 2.13× |
| `string` | concurrent | insert | 1 | 401.1 | 644.1 | 440.9 | 482.8 | 1.10× |
| `string` | concurrent | hit | 1 | 471.4 | 533.4 | 567.3 | 605.1 | 1.20× |
| `string` | concurrent | batch | 1 | 227.2 | 285.3 | 445.3 | 480.3 | 1.96× |
| `string` | concurrent | insert | 8 | 90.3 | 366.0 | 64.6 | 69.5 | 0.72× |
| `string` | concurrent | hit | 8 | 60.3 | 65.9 | 70.4 | 76.1 | 1.17× |
| `string` | concurrent | mixed | 8 | 85.7 | 66.1 | 71.9 | 77.0 | 0.84× |

### AMD Zen 5 (c8a.4xlarge)

| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Best Java / Rust |
|---|---|---|--:|--:|--:|--:|--:|--:|
| `uint64` | single | insert | 1 | 77.5 | 98.1 | 179.0 | 196.6 | 2.31× |
| `uint64` | single | hit | 1 | 46.5 | 42.3 | 277.9 | 297.2 | 5.98× |
| `uint64` | single | miss | 1 | 33.8 | 28.0 | 129.6 | 146.8 | 3.83× |
| `uint64` | single | batch | 1 | 33.7 | 27.1 | 166.6 | 181.9 | 4.94× |
| `uint64` | single | delete | 1 | 100.7 | 94.7 | 254.3 | 280.3 | 2.53× |
| `uint64` | single | scan64 | 1 | 393.3 | 249.2 | 2284.8 | 2724.2 | 5.81× |
| `uint64` | concurrent | insert | 1 | 95.7 | 122.4 | 179.5 | 195.8 | 1.88× |
| `uint64` | concurrent | hit | 1 | 64.6 | 56.7 | 276.9 | 298.7 | 4.29× |
| `uint64` | concurrent | batch | 1 | 43.1 | 41.9 | 163.8 | 180.7 | 3.80× |
| `uint64` | concurrent | insert | 8 | 46.9 | 126.6 | 49.8 | 54.7 | 1.06× |
| `uint64` | concurrent | hit | 8 | 9.7 | 11.5 | 36.7 | 40.2 | 3.80× |
| `uint64` | concurrent | mixed | 8 | 13.2 | 13.1 | 40.1 | 43.3 | 3.04× |
| `string` | single | insert | 1 | 220.4 | 222.9 | 272.2 | 295.2 | 1.24× |
| `string` | single | hit | 1 | 280.5 | 303.0 | 449.5 | 479.2 | 1.60× |
| `string` | single | miss | 1 | 105.9 | 80.2 | 197.0 | 216.8 | 1.86× |
| `string` | single | batch | 1 | 103.0 | 90.6 | 353.2 | 383.5 | 3.43× |
| `string` | single | delete | 1 | 367.0 | 214.2 | 336.0 | 347.7 | 0.92× |
| `string` | single | scan64 | 1 | 1041.8 | 691.5 | 2055.6 | 2059.3 | 1.97× |
| `string` | concurrent | insert | 1 | 270.5 | 303.6 | 273.0 | 292.5 | 1.01× |
| `string` | concurrent | hit | 1 | 408.3 | 318.5 | 443.7 | 473.7 | 1.09× |
| `string` | concurrent | batch | 1 | 137.8 | 134.4 | 351.0 | 383.6 | 2.55× |
| `string` | concurrent | insert | 8 | 81.7 | 257.8 | 63.2 | 65.3 | 0.77× |
| `string` | concurrent | hit | 8 | 54.1 | 42.3 | 58.2 | 62.5 | 1.08× |
| `string` | concurrent | mixed | 8 | 90.3 | 44.3 | 60.5 | 64.7 | 0.67× |

### Intel Sapphire Rapids (c7i.4xlarge)

| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Best Java / Rust |
|---|---|---|--:|--:|--:|--:|--:|--:|
| `uint64` | single | insert | 1 | 141.6 | 149.5 | 328.3 | 362.4 | 2.32× |
| `uint64` | single | hit | 1 | 71.6 | 75.0 | 487.8 | 551.5 | 6.82× |
| `uint64` | single | miss | 1 | 41.9 | 40.2 | 251.6 | 249.4 | 5.96× |
| `uint64` | single | batch | 1 | 46.4 | 46.0 | 265.8 | 278.8 | 5.73× |
| `uint64` | single | delete | 1 | 166.9 | 149.9 | 507.1 | 559.4 | 3.04× |
| `uint64` | single | scan64 | 1 | 551.3 | 427.9 | 3153.7 | 3819.2 | 5.72× |
| `uint64` | concurrent | insert | 1 | 185.1 | 229.5 | 371.4 | 399.5 | 2.01× |
| `uint64` | concurrent | hit | 1 | 107.5 | 122.9 | 519.6 | 537.1 | 4.83× |
| `uint64` | concurrent | batch | 1 | 60.7 | 69.1 | 259.7 | 276.6 | 4.27× |
| `uint64` | concurrent | insert | 8 | 59.4 | 149.7 | 58.9 | 64.8 | 0.99× |
| `uint64` | concurrent | hit | 8 | 12.0 | 14.3 | 48.2 | 48.2 | 4.01× |
| `uint64` | concurrent | mixed | 8 | 15.1 | 17.1 | 53.1 | 57.8 | 3.52× |
| `string` | single | insert | 1 | 348.6 | 357.9 | 494.0 | 555.3 | 1.42× |
| `string` | single | hit | 1 | 496.5 | 515.6 | 787.8 | 885.9 | 1.59× |
| `string` | single | miss | 1 | 168.5 | 152.1 | 398.4 | 477.3 | 2.36× |
| `string` | single | batch | 1 | 152.3 | 141.9 | 592.0 | 633.3 | 3.89× |
| `string` | single | delete | 1 | 548.9 | 364.2 | 727.4 | 726.5 | 1.32× |
| `string` | single | scan64 | 1 | 1352.4 | 878.4 | 2918.7 | 2619.0 | 1.94× |
| `string` | concurrent | insert | 1 | 366.0 | 433.5 | 489.3 | 531.4 | 1.34× |
| `string` | concurrent | hit | 1 | 552.6 | 522.8 | 789.4 | 884.5 | 1.43× |
| `string` | concurrent | batch | 1 | 185.3 | 175.2 | 577.2 | 588.6 | 3.11× |
| `string` | concurrent | insert | 8 | 90.2 | 335.2 | 83.2 | 81.8 | 0.91× |
| `string` | concurrent | hit | 8 | 57.3 | 56.3 | 81.4 | 90.4 | 1.42× |
| `string` | concurrent | mixed | 8 | 99.3 | 58.5 | 86.4 | 94.5 | 0.87× |

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
