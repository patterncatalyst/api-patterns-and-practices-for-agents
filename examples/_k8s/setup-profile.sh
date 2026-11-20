#!/usr/bin/env bash
#
# setup-profile.sh - create (or replace) the single minikube profile that
# hosts the final project's Kubernetes platform: CloudNativePG, Strimzi,
# the otel-lgtm observability image, Keycloak, Apicurio, Istio, Kiali and
# Ollama.
#
# Toolchain: Docker Engine, docker driver, containerd runtime. Fedora or RHEL
# hosts (bare metal or VM).
#
# Idempotent: safe to re-run.
#
# Sizing (overridable via environment):
#   MINIKUBE_CPUS     node CPUs    (default 8)
#   MINIKUBE_MEMORY   node memory  (default 16g)
#   MINIKUBE_DISK     node disk    (default 40g)
#   MINIKUBE_PROFILE  profile name (default apa)
#
# 16 GiB is a laptop-class default, not a measured ceiling: Istio (~150Mi),
# the CNPG operator and a one-instance Postgres cluster (~250Mi), the Strimzi
# operator and a one-node Kafka (~700Mi), the Kiali operator and its CR
# (~150Mi), Keycloak in dev mode (~700Mi-1Gi JVM), Apicurio (~400-500Mi), the
# single otel-lgtm image (~1.5-2Gi, lighter than separate Loki/Grafana/
# Tempo/Mimir releases), Ollama holding one 4B q4_K_M model in memory
# (~3-4Gi) and one chapter app (~400-500Mi) sum to roughly 8-10 GiB of
# requests, leaving headroom for the kube-system pods and burst limits. Raise
# MINIKUBE_MEMORY on a smaller host, or lower it once this is live-verified
# against an actual bring-up.
#
# Host access: every host-facing NodePort (lib/endpoints.sh) is published to
# 127.0.0.1 when the profile is created, via
# `minikube start --ports=127.0.0.1:<hostPort>:<nodePort>,...`. The published
# ports are fixed for the profile's life; to change them, recreate the
# profile with --replace, which deletes the cluster (bootstrap.sh runs
# again afterwards). Nothing else runs on the host for access: no tunnel, no
# proxy, no relay of any kind.
#
# Usage:
#   ./setup-profile.sh             # start (or do nothing if already running)
#   ./setup-profile.sh --replace   # delete first, then start fresh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

PROFILE_NAME="$EP_PROFILE"
MEMORY="${MINIKUBE_MEMORY:-16g}"
CPUS="${MINIKUBE_CPUS:-8}"
DISK="${MINIKUBE_DISK:-40g}"
RUNTIME="containerd"
DRIVER="docker"
KUBERNETES_VERSION="${KUBERNETES_VERSION:-v1.37.2}"
MINIKUBE_FLOOR="v1.39.0"

REPLACE=0
case "${1:-}" in
    --replace) REPLACE=1 ;;
    "") ;;
    *) printf 'unknown flag: %s\nUsage: %s [--replace]\n' "$1" "$(basename "$0")" >&2; exit 2 ;;
esac

# ─── Pre-flight ──────────────────────────────────────────────────────────────

require_tool() {
    local tool="$1" hint="$2"
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf 'ERROR: %s is not in PATH.\n' "$tool" >&2
        printf '  %s\n' "$hint" >&2
        exit 1
    fi
}
require_tool minikube 'Install minikube: https://minikube.sigs.k8s.io/docs/start/'
require_tool kubectl  'Install kubectl: https://kubernetes.io/docs/tasks/tools/'
require_tool helm     'Install helm: https://helm.sh/docs/intro/install/'
require_tool docker   'Install Docker Engine: https://docs.docker.com/engine/install/'

docker_engine_ok || exit 1

# minikube version floor (v1.39.0 is this iteration's pin; see
# _plans/versions.md).
mk_version="$(minikube version --short 2>/dev/null | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' || echo v0.0.0)"
if [[ "$(printf '%s\n' "$MINIKUBE_FLOOR" "$mk_version" | sort -V | head -1)" != "$MINIKUBE_FLOOR" ]]; then
    printf 'ERROR: minikube %s is too old (need >= %s).\n' "$mk_version" "$MINIKUBE_FLOOR" >&2
    exit 1
fi
printf '==> minikube version OK (%s)\n' "$mk_version"

kc_version="$(kubectl version --client 2>/dev/null | sed -n 's/^Client Version: //p')"
if [[ "$kc_version" != "$KUBERNETES_VERSION" ]]; then
    printf 'WARNING: kubectl is %s; this profile runs Kubernetes %s.\n' "${kc_version:-unknown}" "$KUBERNETES_VERSION" >&2
fi

# Engine capacity sanity check: the node cannot be larger than the engine.
engine_cpus="$(docker info --format '{{.NCPU}}' 2>/dev/null || true)"
if [[ "${engine_cpus:-}" =~ ^[0-9]+$ && "$CPUS" =~ ^[0-9]+$ ]] && (( CPUS > engine_cpus )); then
    printf 'ERROR: MINIKUBE_CPUS=%s exceeds the %s CPUs the Docker engine reports.\n' "$CPUS" "$engine_cpus" >&2
    exit 1
fi
printf '==> Docker engine capacity OK (%s CPUs; node wants %s)\n' "${engine_cpus:-?}" "$CPUS"

# An existing profile must already use the docker driver and containerd.
if (( ! REPLACE )) && profile_container_exists; then
    prof_mismatch="$(minikube profile list -o json 2>/dev/null \
        | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
    for p in data.get("valid", []):
        if p.get("Name") == sys.argv[1]:
            cfg = p.get("Config") or {}
            drv = cfg.get("Driver", "")
            rt = (cfg.get("KubernetesConfig") or {}).get("ContainerRuntime", "")
            if drv != "docker" or rt != "containerd":
                print("%s/%s" % (drv or "?", rt or "?"))
except Exception:
    pass
' "$PROFILE_NAME" 2>/dev/null || true)"
    if [[ -n "$prof_mismatch" ]]; then
        printf 'ERROR: profile %s was created with %s; recreate it: ./setup-profile.sh --replace\n' \
            "$PROFILE_NAME" "$prof_mismatch" >&2
        exit 1
    fi
fi

# ─── Profile setup ───────────────────────────────────────────────────────────

if profile_container_exists && (( ! REPLACE )); then
    check_published_ports || exit 1
    if profile_container_running && minikube status -p "$PROFILE_NAME" >/dev/null 2>&1; then
        printf '==> Profile %s already exists and is running. Pass --replace to recreate (deletes the cluster; run bootstrap.sh afterwards).\n' "$PROFILE_NAME"
    else
        own_ports=""
        if profile_container_running; then
            printf '==> Profile %s exists, its container is running but minikube status fails. Restarting it.\n' "$PROFILE_NAME"
            own_ports="$(published_ports | awk '{print $2}' | tr '\n' ' ')"
        else
            printf '==> Profile %s exists but is stopped. Starting it.\n' "$PROFILE_NAME"
        fi
        assert_host_ports_free "$own_ports" || exit 1
        minikube start -p "$PROFILE_NAME"
        check_published_ports || exit 1
    fi
    printf '==> Switching kubectl context to %s\n' "$PROFILE_NAME"
    kubectl config use-context "$PROFILE_NAME"
    printf '==> Done. Current nodes:\n'
    kubectl get nodes
    exit 0
fi

# Create path (fresh or --replace): every host port must be free first,
# except ports this same profile currently publishes while running (a
# --replace frees those itself; a stopped profile holds none).
own_ports=""
if profile_container_running; then
    own_ports="$(published_ports | awk '{print $2}' | tr '\n' ' ')"
fi
assert_host_ports_free "$own_ports" || exit 1

if (( REPLACE )); then
    printf '==> Deleting existing %s profile (--replace specified; run bootstrap.sh afterwards)\n' "$PROFILE_NAME"
    minikube delete -p "$PROFILE_NAME"
fi

printf '==> Starting %s profile (Kubernetes %s, %s RAM, %s CPUs, %s disk, %s driver, %s runtime)\n' \
    "$PROFILE_NAME" "$KUBERNETES_VERSION" "$MEMORY" "$CPUS" "$DISK" "$DRIVER" "$RUNTIME"

PORTS_ARG="$(node_ports_arg)"
printf '==> Publishing NodePorts on 127.0.0.1: %s\n' "$PORTS_ARG"

minikube start -p "$PROFILE_NAME" \
    --ports="$PORTS_ARG" \
    --memory="$MEMORY" \
    --cpus="$CPUS" \
    --disk-size="$DISK" \
    --driver="$DRIVER" \
    --container-runtime="$RUNTIME" \
    --kubernetes-version="$KUBERNETES_VERSION" \
    --addons=metrics-server

check_published_ports || {
    printf 'ERROR: the node did not publish the required NodePorts on 127.0.0.1 (loopback is required).\n' >&2
    exit 1
}

printf '==> Switching kubectl context to %s\n' "$PROFILE_NAME"
kubectl config use-context "$PROFILE_NAME"

printf '==> Verifying cluster health\n'
kubectl get nodes
kubectl get pods -n kube-system

printf '\n==> Profile is ready.\n\n'
printf 'Next steps:\n'
printf '  ./bootstrap.sh         # bring up the full platform, tiered and health-gated\n'
printf '  ./load-images.sh       # build and load any chapter app images\n'
printf '  ./show-endpoints.sh    # print the published host:nodePort map\n'
printf '  ./teardown.sh          # delete this profile\n'
