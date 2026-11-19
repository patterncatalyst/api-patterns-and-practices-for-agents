#!/usr/bin/env bash
# Builds the second distribution channel for the apa-order-ops skill: a packaged `.skill`
# bundle, a zip archive whose single top-level entry is the skill's own name
# (apa-order-ops/SKILL.md, apa-order-ops/references/..., apa-order-ops/scripts/...), the shape a
# packaged Claude Code skill takes when imported rather than picked up as an in-repo project
# skill. ../../skills/apa-order-ops/ (the first channel) is reused unchanged as the input; this
# script only archives it.
#
# Usage: ./package-skill.sh [output-path]
# Defaults to dist/apa-order-ops.skill under this example's own directory. The built artifact is
# never committed: verify.sh builds it fresh into a scratch directory and removes it afterward.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

SKILL_NAME=apa-order-ops
SKILL_DIR=../../skills/$SKILL_NAME
OUT="${1:-dist/$SKILL_NAME.skill}"

if [[ ! -f "$SKILL_DIR/SKILL.md" ]]; then
    echo "package-skill.sh: missing $SKILL_DIR/SKILL.md; run this from examples/23-building-a-claude-code-skill" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
rm -f "$OUT"

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp -R "$SKILL_DIR" "$STAGE/$SKILL_NAME"

# python3's zipfile, not the zip(1) binary, for the same reason the rest of this book reaches
# for jq and python3 over ad hoc shell tooling: it is already a required toolchain dependency
# and its archive member paths are explicit, never shell-glob-dependent.
python3 -I - "$STAGE" "$SKILL_NAME" "$OUT" <<'PY'
import pathlib
import sys
import zipfile

stage, skill_name, out = sys.argv[1:4]
src = pathlib.Path(stage) / skill_name
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
    for p in sorted(src.rglob("*")):
        if p.is_file():
            zf.write(p, pathlib.Path(skill_name) / p.relative_to(src))
PY

echo "$OUT"
