#!/usr/bin/env bash
# Runs the workloads of bbtree-go on the Go port, on the Rust bbtree, and on this Java port, with
# the same flags, keys and checksums. The rounds interleave the languages.
#
# Needs: go 1.25 or later, cargo with edition 2024, Java 25, and the benchmark jar
# (mvn -B package -DskipTests). Usage: bbtree-benchmarks/cross-language.sh [rounds] > results.csv
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$root/bbtree-benchmarks/target/benchmarks.jar"
rounds="${1:-6}"
# A directory of the current user, not a shared one such as /tmp, because the script runs the
# programs that it finds there.
cache="${BBTREE_BENCH_CACHE:-$HOME/.cache/bbtree-cross-language}"
go_revision=262b0b294d30c16d1b0fe00a02d9dd0312bc9000
mkdir -p -m 700 "$cache"
if [[ ! -O "$cache" ]] || [[ -n "$(find "$cache" -maxdepth 0 -perm -g+w -o -maxdepth 0 -perm -o+w)" ]]; then
  echo "$cache must belong to you, and other users must not write to it" >&2
  exit 1
fi
if [[ ! -d "$cache/bbtree-go/.git" ]]; then
  git clone --quiet https://github.com/richardartoul/bbtree-go "$cache/bbtree-go"
fi
git -C "$cache/bbtree-go" checkout --quiet "$go_revision"
if [[ "$(git -C "$cache/bbtree-go" rev-parse HEAD)" != "$go_revision" ]]; then
  echo "bbtree-go is not at $go_revision" >&2
  exit 1
fi
# The script of bbtree-go builds its Go binary and the Rust reference again on each run, at a
# pinned revision of the Rust bbtree. A short run builds them.
BBTREE_BENCH_CACHE="$cache" bash "$cache/bbtree-go/bench/run.sh" -n 64 -queries 64 > /dev/null

cases=(
  "single 1 insert" "single 1 hit" "single 1 miss" "single 1 batch" "single 1 delete"
  "single 1 scan64" "concurrent 1 insert" "concurrent 1 hit" "concurrent 1 batch"
  "concurrent 8 insert" "concurrent 8 hit" "concurrent 8 mixed"
)
echo 'round,language,kind,mode,operation,threads,entries,operations,ns/op,checksum,timed_allocated_bytes'
for round in $(seq 1 "$rounds"); do
  for kind in uint64 string; do
    for c in "${cases[@]}"; do
      read -r mode threads op <<< "$c"
      args=(-kind "$kind" -mode "$mode" -threads "$threads" -op "$op")
      "$cache/go-bench" "${args[@]}" | sed "s/^/$round,/"
      "$cache/reference/target/release/bbtree-reference" "${args[@]}" | sed "s/^/$round,/"
      for impl in scalar vector; do
        # The last of 5 repetitions in one JVM, after the JIT warm-up.
        java -Xms8g -Xmx8g --add-modules=jdk.incubator.vector -cp "$jar" \
          io.github.sb.bbtree.bench.CrossLanguage "${args[@]}" -impl "$impl" -repeat 5 2> /dev/null \
          | grep -v '^Picked' | tail -1 | sed "s/^/$round,/"
      done
    done
  done
done
