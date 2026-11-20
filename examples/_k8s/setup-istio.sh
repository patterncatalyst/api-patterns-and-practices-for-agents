#!/usr/bin/env bash
#
# setup-istio.sh - install the Istio control plane (Helm: base + istiod
# charts, no istioctl dependency) and enable mesh membership for the apa
# namespace, where chapter apps land in a later wave.
#
# Chart source: the pinned Istio release tarball, checksum-verified. Istio
# 1.32 charts are not published to the istio-release Helm repository (its
# index stops well short of 1.32.x), so the charts come from the release's
# own manifests/charts/ directory. The tarball is downloaded once and kept at
# ~/.local/share/istio-<version>.
#
# Pin (see _plans/versions.md): Istio 1.32.0, Kubernetes 1.33-1.37 supported.
#
# NAMESPACE-WIDE injection on apa, by design (a deliberate divergence from
# the sibling datamesh-reference-arch-quarkus platform, which injects
# per-Deployment because its single app namespace also held unmeshed infra).
# Here, platform data services (CloudNativePG, Strimzi, Keycloak, Apicurio,
# Ollama) live in the SEPARATE apa-data namespace, which is never labeled for
# injection, so the known collisions that namespace-wide injection would
# otherwise cause - a one-shot Job pod that never exits because its sidecar
# keeps running, or a database operator's own TLS handshake colliding with
# the sidecar's mTLS wrapping - cannot happen: nothing in apa-data ever gets
# a sidecar. The apa namespace carries only application Deployments, where
# namespace-wide injection is safe and simplest. base/namespace.yaml carries
# the injection label directly, so the mesh opt-in exists from the moment the
# namespace is created, before Istio itself is even installed; this script
# re-applies the same label defensively, in case it runs before base/ has
# ever been applied.
#
# Istio 1.29+ uses a native sidecar: istio-proxy runs as an initContainer
# with restartPolicy: Always. A meshed pod still reports 2/2 Ready; a
# membership check must inspect .spec.initContainers, not just
# .spec.containers.
#
# Scope: control plane only, no ingress gateway (host access to the platform
# is NodePorts, never an Istio gateway).
#
# Idempotent: helm upgrade --install.
#
# Usage:
#   ./setup-istio.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

APP_NS="apa"
PROFILE_NAME="$EP_PROFILE"
ISTIO_SYSTEM="istio-system"
ISTIO_VERSION="${ISTIO_VERSION:-1.32.0}"
ISTIO_ARCH="${ISTIO_ARCH:-amd64}"
ISTIO_HOME="${ISTIO_HOME:-${HOME}/.local/share/istio-${ISTIO_VERSION}}"
BASE_CHART="${ISTIO_HOME}/manifests/charts/base"
ISTIOD_CHART="${ISTIO_HOME}/manifests/charts/istio-control/istio-discovery"

step() { printf '\n==> %s\n' "$1"; }

command -v kubectl   >/dev/null 2>&1 || { printf 'ERROR: kubectl not in PATH.\n' >&2; exit 1; }
command -v helm      >/dev/null 2>&1 || { printf 'ERROR: helm not in PATH.\n' >&2; exit 1; }
command -v curl      >/dev/null 2>&1 || { printf 'ERROR: curl not in PATH.\n' >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { printf 'ERROR: sha256sum not in PATH.\n' >&2; exit 1; }

current_context="$(kubectl config current-context 2>/dev/null || echo "")"
if [[ "$current_context" != "$PROFILE_NAME" ]]; then
    printf 'ERROR: kubectl context is "%s", expected "%s". Run setup-profile.sh first.\n' "$current_context" "$PROFILE_NAME" >&2
    exit 1
fi

kubectl create namespace "$APP_NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$APP_NS" istio-injection=enabled --overwrite >/dev/null

# ─── 1. Istio release (charts), pinned and checksum-verified ────────────────

step "Ensuring Istio ${ISTIO_VERSION} is unpacked at ${ISTIO_HOME}"
if [[ -f "${BASE_CHART}/Chart.yaml" && -f "${ISTIOD_CHART}/Chart.yaml" ]]; then
    printf 'Already present.\n'
else
    tarball="istio-${ISTIO_VERSION}-linux-${ISTIO_ARCH}.tar.gz"
    url="https://github.com/istio/istio/releases/download/${ISTIO_VERSION}/${tarball}"
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL -o "${tmp}/${tarball}" "$url"
    curl -fsSL -o "${tmp}/${tarball}.sha256" "${url}.sha256"
    (cd "$tmp" && sha256sum -c "${tarball}.sha256") \
        || { printf 'ERROR: checksum mismatch for %s\n' "$tarball" >&2; exit 1; }
    mkdir -p "$(dirname "$ISTIO_HOME")"
    tar -xzf "${tmp}/${tarball}" -C "$tmp"
    rm -rf "$ISTIO_HOME"
    mv "${tmp}/istio-${ISTIO_VERSION}" "$ISTIO_HOME"
fi
for chart in "$BASE_CHART" "$ISTIOD_CHART"; do
    chart_version="$(sed -n 's/^version: *//p' "${chart}/Chart.yaml")"
    [[ "$chart_version" == "$ISTIO_VERSION" ]] \
        || { printf 'ERROR: %s is chart version %s, expected %s.\n' "$chart" "$chart_version" "$ISTIO_VERSION" >&2; exit 1; }
done

# ─── 2. Minor-version skip guard ─────────────────────────────────────────────

running_tag="$(kubectl get deployment istiod -n "$ISTIO_SYSTEM" \
    -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null | sed 's/.*://' || true)"
if [[ "$running_tag" =~ ^1\.([0-9]+)\. ]]; then
    running_minor="${BASH_REMATCH[1]}"
    pinned_minor="$(cut -d. -f2 <<<"$ISTIO_VERSION")"
    if (( pinned_minor - running_minor > 1 )); then
        printf 'ERROR: istiod %s is running; Istio cannot upgrade in place to %s (more than one minor version).\n' "$running_tag" "$ISTIO_VERSION" >&2
        printf 'Remove it first: helm uninstall istiod istio-base -n %s (or setup-profile.sh --replace).\n' "$ISTIO_SYSTEM" >&2
        exit 1
    fi
fi

# ─── 3. base CRDs, then istiod ───────────────────────────────────────────────

step "Installing istio-base (CRDs) ${ISTIO_VERSION} into ${ISTIO_SYSTEM}"
helm upgrade --install istio-base "$BASE_CHART" \
    --namespace "$ISTIO_SYSTEM" --create-namespace \
    --set defaultRevision=default

step "Installing istiod ${ISTIO_VERSION}"
helm upgrade --install istiod "$ISTIOD_CHART" \
    --namespace "$ISTIO_SYSTEM" \
    --wait --timeout 5m

step "Waiting for istiod to be Available"
kubectl rollout status deployment/istiod -n "$ISTIO_SYSTEM" --timeout=5m

# ─── 4. STRICT mTLS on the (meshed) apa namespace only ──────────────────────

step "Applying STRICT PeerAuthentication to ${APP_NS}"
kubectl apply -n "$APP_NS" -f - <<EOF
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata:
  name: default
  namespace: ${APP_NS}
spec:
  mtls:
    mode: STRICT
EOF

# ─── Done ────────────────────────────────────────────────────────────────────

step "Istio control plane is installed."
printf '\nNamespace %s is labeled for mesh membership (istio-injection: enabled);\n' "$APP_NS"
printf 'apa-data, observability and %s are not meshed.\n' "$ISTIO_SYSTEM"
printf '\nNext: ./setup-kiali.sh   (mesh-topology UI wired to the otel-lgtm Prometheus endpoint)\n'
