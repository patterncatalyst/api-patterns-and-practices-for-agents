#!/usr/bin/env bash
#
# load-images.sh - build the chapter app images this platform deploys and
# load them into the minikube profile's container runtime.
#
# The profile has no image registry and runs the containerd runtime (so
# `eval $(minikube docker-env)` does not apply - that only works with the
# docker runtime). Instead, build with the host's docker and copy each image
# in with `minikube image load`.
#
# Build context is `examples/` (the Maven reactor root) for every service,
# matching the Containerfile convention every chapter example already uses
# (see examples/17-event-triggered-agent-wakeup/Containerfile): the build
# stage copies examples/domain-model/ so it resolves as a reactor module.
#
# Extending this for a new chapter needs no edit here: any
# examples/NN-slug/Containerfile is discovered automatically. This book's
# final project (Part 7) is the only consumer as of this wave; no application
# images exist yet.
#
# Usage:
#   ./load-images.sh                  # build + load every discovered chapter image
#   ./load-images.sh 37-slug          # one or more named chapter directories
#   SKIP_BUILD=1 ./load-images.sh     # load images already built on the host
#
# Environment:
#   MINIKUBE_PROFILE   profile to load into (default: apa)
#   SKIP_BUILD         1 = skip docker build, load existing host images

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLES_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROFILE="${MINIKUBE_PROFILE:-apa}"
TAG="latest"
SKIP_BUILD="${SKIP_BUILD:-0}"

fail() { printf '\n\xe2\x9c\x97 %s\n' "$1" >&2; exit 1; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

command -v docker   >/dev/null 2>&1 || fail "docker not in PATH"
command -v minikube >/dev/null 2>&1 || fail "minikube not in PATH"
minikube status -p "$PROFILE" >/dev/null 2>&1 \
    || fail "minikube profile '${PROFILE}' is not running; run ./setup-profile.sh first"

shopt -s nullglob
if (( $# > 0 )); then
    SLUGS=("$@")
else
    SLUGS=()
    for d in "${EXAMPLES_DIR}"/[0-9][0-9]-*/ "${EXAMPLES_DIR}"/a[a-e]-*/; do
        [[ -f "${d}Containerfile" ]] && SLUGS+=("$(basename "$d")")
    done
fi
shopt -u nullglob

if (( ${#SLUGS[@]} == 0 )); then
    printf 'No chapter application images found yet (this wave ships the platform only).\n'
    printf 'Once a chapter lands its own examples/NN-slug/Containerfile, re-run this script.\n'
    exit 0
fi

for slug in "${SLUGS[@]}"; do
    cf="${EXAMPLES_DIR}/${slug}/Containerfile"
    [[ -f "$cf" ]] || fail "no Containerfile for '${slug}' at examples/${slug}/Containerfile"
done

for slug in "${SLUGS[@]}"; do
    image="apa/${slug}:${TAG}"
    if [[ "$SKIP_BUILD" != "1" ]]; then
        step "build ${image}"
        docker build -f "examples/${slug}/Containerfile" -t "$image" "$EXAMPLES_DIR" \
            || fail "docker build failed for ${slug}"
    else
        docker image inspect "$image" >/dev/null 2>&1 \
            || fail "SKIP_BUILD=1 but ${image} is not on the host; build it first"
    fi
    step "load ${image} into profile ${PROFILE}"
    minikube image load "$image" -p "$PROFILE" || fail "minikube image load failed for ${slug}"
done

step "images in profile ${PROFILE}"
minikube image ls -p "$PROFILE" | grep -E '^apa/' || true

# Restart Deployments that already exist so they pick up the new image.
if command -v kubectl >/dev/null 2>&1; then
    for slug in "${SLUGS[@]}"; do
        if kubectl --context "$PROFILE" get deployment "$slug" -n apa >/dev/null 2>&1; then
            kubectl --context "$PROFILE" rollout restart "deployment/${slug}" -n apa >/dev/null \
                && printf '  restarted deployment/%s\n' "$slug"
            kubectl --context "$PROFILE" rollout status "deploy/${slug}" -n apa --timeout=180s >/dev/null \
                || printf '    WARN: deploy/%s did not finish rolling out within 180s\n' "$slug" >&2
        fi
    done
fi

printf '\n==> Done. Deploy or update the apps with: kubectl apply -k examples/_k8s/overlays/minikube\n'
