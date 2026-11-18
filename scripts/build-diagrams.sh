#!/usr/bin/env bash
# Regenerate every figure: assets/diagrams/specs/<name>.py -> assets/diagrams/<name>.svg + <name>.excalidraw.
# Never hand-edit the outputs; edit the spec and run this.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mkdir -p assets/diagrams
shopt -s nullglob
specs=(assets/diagrams/specs/*.py)
if [[ ${#specs[@]} -eq 0 ]]; then
    echo "build-diagrams: no specs yet"
    exit 0
fi
export PYTHONPATH="$PWD/scripts${PYTHONPATH:+:$PYTHONPATH}"
export PYTHONDONTWRITEBYTECODE=1
for spec in "${specs[@]}"; do
    python3 -c "
import runpy, sys, generate_diagram
generate_diagram.OUT = 'assets/diagrams'
runpy.run_path(sys.argv[1], run_name='__main__')
" "$spec"
done
echo "build-diagrams: ${#specs[@]} spec(s) built"
