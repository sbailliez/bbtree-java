#!/usr/bin/env bash
# Counts hardware events per operation for one search variant and one mode of CounterProbe.
# Needs Linux, perf, and sudo for perf. Usage: counter-probe.sh <variant> <mode> [operations]
# Example: counter-probe.sh scalar hit-cold
set -euo pipefail
variant=$1
mode=$2
ops=${3:-4000000}
jar="${JAR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/target/benchmarks.jar}"
if [[ "$(uname -m)" == aarch64 ]]; then
  pmu=armv8_pmuv3_0
  events="instructions,$pmu/inst_spec/,$pmu/br_mis_pred_retired/,$pmu/l2d_cache_lmiss_rd/,$pmu/stall_backend_mem/"
else
  events="instructions,branch-misses,cache-misses,cycles"
fi
out=$(mktemp)
trap 'rm -f "$out" "$out.perf"' EXIT
java -Xms8g -Xmx8g -XX:+AlwaysPreTouch --add-modules=jdk.incubator.vector -cp "$jar" \
  io.github.sb.bbtree.CounterProbe "$variant" "$mode" "$ops" > "$out" 2> /dev/null &
pid=$!
until grep -q READY "$out"; do sleep 0.2; done
sudo perf stat -x, -e "$events" -p "$pid" 2> "$out.perf"
wait "$pid"
grep ns/op "$out"
awk -F, -v ops="$ops" '$1 ~ /^[0-9]+$/ {printf "  %-40s %10.1f per operation\n", $3, $1 / ops}' "$out.perf"
