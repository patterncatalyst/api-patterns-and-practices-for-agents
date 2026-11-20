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
# examples/NN-slug/Containerfile is discovered automatically. One slug builds two images instead
# of one: examples/15-reshape-grpc-behind-facade ships no top-level Containerfile, only
# mcp-facade/Containerfile and payment-service/Containerfile (two separate modules, two separate
# runtime images), each already built with the repo root as its context (see each Containerfile's
# own header: it COPYs contracts/15-payment.proto, which lives outside examples/). TWO_IMAGE_SLUGS
# below is the one place that lists a slug built this way; a future chapter with the same shape
# adds its slug there, nothing else needs to change.
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
REPO_ROOT="$(cd "${EXAMPLES_DIR}/.." && pwd)"
PROFILE="${MINIKUBE_PROFILE:-apa}"

# Slugs that ship two sub-module Containerfiles instead of one top-level Containerfile, for
# example a gRPC backend built apart from the MCP facade in front of it. two_image_modules prints
# the module directory names (relative to examples/<slug>/), one per line; each module's
# Containerfile is built with the REPO ROOT as context (not examples/), since it COPYs a committed
# contract from outside examples/ (see examples/15-reshape-grpc-behind-facade/*/Containerfile's own
# header comment). A future chapter with the same two-image shape adds its slug and modules here;
# nothing else in this script changes.
two_image_modules() {
    case "$1" in
        15-reshape-grpc-behind-facade) printf 'mcp-facade\npayment-service\n' ;;
        *) return 1 ;;
    esac
}
is_two_image_slug() { two_image_modules "$1" >/dev/null 2>&1; }
# `localhost/apa/<slug>:<tag>` (never `apa/<slug>:latest`): the `localhost/` prefix is the
# explicit, no-registry local-image convention `scripts/validate.py`'s image-pin check already
# requires for every other image in this book (a bare `apa/...` name reads as an unqualified
# registry reference), and the tag matches each example's own Maven `<version>` (every example
# packages 1.0.0), never the floating `latest` tag the house versioning rule bans. Override only
# to load a different build of the same example.
TAG="${IMAGE_TAG:-1.0.0}"
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
        slug="$(basename "$d")"
        if [[ -f "${d}Containerfile" ]] || is_two_image_slug "$slug"; then
            SLUGS+=("$slug")
        fi
    done
fi
shopt -u nullglob

if (( ${#SLUGS[@]} == 0 )); then
    printf 'No chapter application images found yet (this wave ships the platform only).\n'
    printf 'Once a chapter lands its own examples/NN-slug/Containerfile, re-run this script.\n'
    exit 0
fi

for slug in "${SLUGS[@]}"; do
    if is_two_image_slug "$slug"; then
        while IFS= read -r module; do
            cf="${EXAMPLES_DIR}/${slug}/${module}/Containerfile"
            [[ -f "$cf" ]] || fail "no Containerfile for '${slug}/${module}' at examples/${slug}/${module}/Containerfile"
        done < <(two_image_modules "$slug")
    else
        cf="${EXAMPLES_DIR}/${slug}/Containerfile"
        [[ -f "$cf" ]] || fail "no Containerfile for '${slug}' at examples/${slug}/Containerfile"
    fi
done

build_and_load() { # build_and_load <image> <containerfile> <context>
    local image="$1" cf="$2" ctx="$3"
    if [[ "$SKIP_BUILD" != "1" ]]; then
        step "build ${image}"
        docker build -f "$cf" -t "$image" "$ctx" || fail "docker build failed for ${image}"
    else
        docker image inspect "$image" >/dev/null 2>&1 \
            || fail "SKIP_BUILD=1 but ${image} is not on the host; build it first"
    fi
    step "load ${image} into profile ${PROFILE}"
    minikube image load "$image" -p "$PROFILE" || fail "minikube image load failed for ${image}"
}

for slug in "${SLUGS[@]}"; do
    if is_two_image_slug "$slug"; then
        while IFS= read -r module; do
            image="localhost/apa/${slug}-${module}:${TAG}"
            cf="${EXAMPLES_DIR}/${slug}/${module}/Containerfile"
            # Repo-root context: each module's own Containerfile COPYs a committed contract from
            # outside examples/ (contracts/15-payment.proto), so examples/ alone cannot resolve it.
            build_and_load "$image" "$cf" "$REPO_ROOT"
        done < <(two_image_modules "$slug")
    else
        image="localhost/apa/${slug}:${TAG}"
        build_and_load "$image" "${EXAMPLES_DIR}/${slug}/Containerfile" "$EXAMPLES_DIR"
    fi
done

step "images in profile ${PROFILE}"
minikube image ls -p "$PROFILE" | grep -E '^localhost/apa/' || true

# Restart Deployments named identically to their example slug so they pick up the new image. A
# Deployment named after the application instead (an existing example reused unchanged, for
# instance) is not matched here; its own fresh `kubectl delete -k ... && kubectl apply -k ...`
# cycle (every example's verify.sh) already picks up a freshly loaded image without this step.
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
