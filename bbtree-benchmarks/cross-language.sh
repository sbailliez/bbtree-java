#!/usr/bin/env bash
# Runs the workloads of bbtree-go on the Go port, on the Rust bbtree, and on this Java port, with
# the same flags, keys and checksums. The rounds interleave the languages.
#
# Commands:
#   prepare <dir>                 Needs internet. Clones bbtree-go and the Rust bbtree at pinned
#                                 revisions, stores the Rust crates in <dir> (cargo vendor), and
#                                 copies the benchmark jar. Run "mvn -B package -DskipTests" first.
#   run <dir> [rounds]            Works offline. Builds Go and Rust from <dir>, then prints CSV.
#   remote <host> <dir> [rounds]  Copies <dir> to an SSH host, installs Java 25, Go and Rust with
#                                 dnf if they are missing (Amazon Linux 2023), runs there, and
#                                 prints the CSV. Put jump hosts in ~/.ssh/config or in SSH_OPTS.
#   table <csv>                   Prints the median of each case as a Markdown table.
#
# Example on this machine:   cross-language.sh prepare ~/.cache/bbtree-cross
#                            cross-language.sh run ~/.cache/bbtree-cross 6 > m5.csv
# Example on an EC2 host:    cross-language.sh remote ec2-user@10.0.0.5 ~/.cache/bbtree-cross > c7g.csv
#                            cross-language.sh table c7g.csv
#
# Needs: go 1.25 or later, cargo with edition 2024, Java 25.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
go_revision=262b0b294d30c16d1b0fe00a02d9dd0312bc9000
rust_revision=8adae97a0ed97e5c98fa2790312eb0aec9719846

# The script runs the programs that it builds in <dir>, so <dir> must be private.
check_private() {
  mkdir -p -m 700 "$1"
  if [[ ! -O "$1" ]] || [[ -n "$(find "$1" -maxdepth 0 -perm -g+w -o -maxdepth 0 -perm -o+w)" ]]; then
    echo "$1 must belong to you, and other users must not write to it" >&2
    exit 1
  fi
}

checkout() {
  local url=$1 dir=$2 revision=$3
  if [[ ! -d "$dir/.git" ]]; then
    git clone --quiet "$url" "$dir"
  fi
  git -C "$dir" fetch --quiet origin
  git -C "$dir" checkout --quiet "$revision"
  if [[ "$(git -C "$dir" rev-parse HEAD)" != "$revision" ]]; then
    echo "$dir is not at $revision" >&2
    exit 1
  fi
}

prepare() {
  local dir=$1
  check_private "$dir"
  checkout https://github.com/richardartoul/bbtree-go "$dir/bbtree-go" "$go_revision"
  checkout https://github.com/XiangpengHao/bbtree "$dir/bbtree" "$rust_revision"
  # The Rust reference program of bbtree-go, as its bench/run.sh makes it.
  mkdir -p "$dir/reference/src" "$dir/reference/.cargo"
  cp "$dir/bbtree-go/bench/reference.rs" "$dir/reference/src/main.rs"
  cat > "$dir/reference/Cargo.toml" << 'EOF'
[package]
name = "bbtree-reference"
version = "0.0.0"
edition = "2024"
[dependencies]
bbtree = { path = "../bbtree/bbtree" }
[profile.release]
lto = "fat"
codegen-units = 1
debug = 1
EOF
  (cd "$dir/reference" && cargo vendor --versioned-dirs vendor > .cargo/config.toml 2> /dev/null)
  cp "$root/bbtree-benchmarks/target/benchmarks.jar" "$dir/benchmarks.jar"
  cp "${BASH_SOURCE[0]}" "$dir/cross-language.sh"
  echo "prepared $dir" >&2
}

build() {
  local dir=$1
  (cd "$dir/bbtree-go" && GOTOOLCHAIN=local GOFLAGS=-mod=mod go build -o "$dir/go-bench" ./bench)
  (cd "$dir/reference" && RUSTFLAGS="-C target-cpu=native" cargo build --quiet --offline --release)
}

run() {
  local dir=$1 rounds=${2:-6}
  build "$dir"
  local cases=(
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
        "$dir/go-bench" "${args[@]}" | sed "s/^/$round,/"
        "$dir/reference/target/release/bbtree-reference" "${args[@]}" | sed "s/^/$round,/"
        for impl in scalar vector; do
          # The last of 5 repetitions in one JVM, after the JIT warm-up.
          java -Xms8g -Xmx8g --add-modules=jdk.incubator.vector -cp "$dir/benchmarks.jar" \
            io.github.sb.bbtree.bench.CrossLanguage "${args[@]}" -impl "$impl" -repeat 5 2> /dev/null \
            | grep -v '^Picked' | tail -1 | sed "s/^/$round,/"
        done
      done
    done
    echo "round $round done" >&2
  done
}

remote() {
  local host=$1 dir=$2 rounds=${3:-6}
  local remote_dir=bbtree-cross
  # shellcheck disable=SC2086
  rsync -az --delete --exclude /reference/target --exclude /go-bench \
    -e "ssh -o BatchMode=yes ${SSH_OPTS:-}" "$dir/" "$host:$remote_dir/"
  # shellcheck disable=SC2086
  ssh -o BatchMode=yes ${SSH_OPTS:-} "$host" bash -s -- "$remote_dir" "$rounds" << 'EOF'
set -euo pipefail
dir="$HOME/$1"
chmod 700 "$dir"
missing=()
command -v go > /dev/null || missing+=(golang)
command -v cargo > /dev/null || missing+=(rust cargo)
java -version 2>&1 | grep -q '"25' || missing+=(java-25-amazon-corretto-devel)
command -v gcc > /dev/null || missing+=(gcc)
if (( ${#missing[@]} )); then
  sudo dnf install -y -q "${missing[@]}" >&2
fi
uname -m >&2
lscpu | grep 'Model name' >&2 || true
"$dir/cross-language.sh" run "$dir" "$2"
EOF
}

table() {
  python3 - "$1" << 'EOF'
import csv, statistics, sys
rows = list(csv.DictReader(open(sys.argv[1])))
cases = {}
for r in rows:
    key = (r["kind"], r["mode"], r["operation"], r["threads"])
    cases.setdefault(key, {}).setdefault(r["language"], []).append(float(r["ns/op"]))
langs = ["rust", "go", "java", "java-vector"]
print("| Keys | Map | Operation | Threads | Rust | Go | Java | Java, vector | Best Java / Rust |")
print("|---|---|---|--:|--:|--:|--:|--:|--:|")
for (kind, mode, op, threads), v in cases.items():
    m = {l: statistics.median(v[l]) for l in langs if l in v}
    best = min(m.get("java", float("inf")), m.get("java-vector", float("inf")))
    cells = " | ".join(f"{m[l]:.1f}" if l in m else "-" for l in langs)
    print(f"| `{kind}` | {mode} | {op} | {threads} | {cells} | {best / m['rust']:.2f}× |")
EOF
}

command=${1:-}
case "$command" in
  prepare) prepare "$2" ;;
  run) run "$2" "${3:-6}" ;;
  remote) remote "$2" "$3" "${4:-6}" ;;
  table) table "$2" ;;
  *)
    sed -n '2,22p' "${BASH_SOURCE[0]}" >&2
    exit 1
    ;;
esac
