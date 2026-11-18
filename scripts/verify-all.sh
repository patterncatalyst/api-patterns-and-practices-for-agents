#!/usr/bin/env bash
# Run every example's verify.sh, one at a time, from fresh state; stop on the
# first failure; print a summary. r01 ships no examples yet, so this is a
# stub: it discovers examples/*/verify.sh and runs them, or says plainly that
# there is nothing to run.
#
#   scripts/verify-all.sh [--list]
#
# RUN_DIR (default: a new directory under ${TMPDIR:-/tmp}) receives one
# scrubbed log per example (filtered through scripts/host_ids.py, so no host
# identifier is kept) and results.tsv: example, exit, wall_s.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"
shopt -s nullglob

list=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --list) list=1 ;;
        *) echo "usage: scripts/verify-all.sh [--list]" >&2; exit 2 ;;
    esac
    shift
done

entries=()
for d in examples/*/; do
    d="${d%/}"
    [[ -f "$d/verify.sh" ]] && entries+=("${d#examples/}")
done

if [[ ${#entries[@]} -eq 0 ]]; then
    echo "verify-all: no examples yet (examples/*/verify.sh not found)"
    exit 0
fi

if [[ $list -eq 1 ]]; then
    printf '%s\n' "${entries[@]}"
    exit 0
fi

RUN_DIR="${RUN_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/verify-all.XXXXXX")}"
mkdir -p "$RUN_DIR"
TSV="$RUN_DIR/results.tsv"
printf 'example\texit\twall_s\n' > "$TSV"
scrub() { python3 -I "$ROOT/scripts/host_ids.py" filter; }

passed=()
for name in "${entries[@]}"; do
    log="$RUN_DIR/${name}.log"
    echo "==> $name"
    t0=$SECONDS
    (cd "examples/$name" && ./verify.sh) 2>&1 | scrub > "$log"
    rc=${PIPESTATUS[0]}
    wall=$((SECONDS - t0))
    printf '%s\t%s\t%s\n' "$name" "$rc" "$wall" >> "$TSV"
    echo "    exit $rc, ${wall}s"
    if [[ $rc -ne 0 ]]; then
        echo; echo "verify-all: ${#passed[@]} passed"; printf '  ok   %s\n' "${passed[@]}"
        echo "  FAIL $name (log $log)"; echo "verify-all: results in $TSV"
        exit 1
    fi
    passed+=("$name")
done
echo; echo "verify-all: ${#passed[@]} passed"; printf '  ok   %s\n' "${passed[@]}"
echo "verify-all: results in $TSV"
