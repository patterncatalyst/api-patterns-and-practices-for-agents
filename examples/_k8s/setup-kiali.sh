#!/usr/bin/env bash
#
# setup-kiali.sh - install the kiali-operator (Helm) and apply a Kiali custom
# resource, wired to the EXISTING otel-lgtm observability image
# (setup-lgtm.sh) instead of a standalone Prometheus/Grafana/tracing backend.
#
# Pin (see _plans/versions.md): Kiali 2.35.0 (server and operator are
# versioned identically). Operator + CR, not the kiali-server chart directly:
# the kiali-operator watches for a Kiali resource and reconciles the same
# Deployment/Service/ConfigMap shape the kiali-server chart would have
# produced on its own.
#
# SINGLE-STACK WIRING: otel-lgtm bundles its own Prometheus, reachable at
# otel-lgtm.<obs-ns>.svc.cluster.local:9090 with the standard Prometheus HTTP
# API at the root path (no /prometheus prefix, unlike a Mimir-backed setup).
#
# Prerequisites: setup-istio.sh (istiod), setup-lgtm.sh (otel-lgtm).
#
# Idempotent: helm upgrade --install, kubectl apply.
#
# Usage:
#   ./setup-kiali.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

APP_NS="apa"
PROFILE_NAME="$EP_PROFILE"
ISTIO_SYSTEM="istio-system"
OBS_NS="$EP_OBS_NS"
KIALI_VERSION="${KIALI_VERSION:-2.35.0}"

PROM_URL="http://otel-lgtm.${OBS_NS}.svc.cluster.local:9090"
GRAFANA_IN_URL="http://otel-lgtm.${OBS_NS}.svc.cluster.local:3000"
GRAFANA_EXT_URL="http://localhost:${TP_GRAFANA}"
TEMPO_URL="http://otel-lgtm.${OBS_NS}.svc.cluster.local:3200"

step() { printf '\n==> %s\n' "$1"; }

command -v kubectl >/dev/null 2>&1 || { printf 'ERROR: kubectl not in PATH.\n' >&2; exit 1; }
command -v helm    >/dev/null 2>&1 || { printf 'ERROR: helm not in PATH.\n' >&2; exit 1; }

current_context="$(kubectl config current-context 2>/dev/null || echo "")"
if [[ "$current_context" != "$PROFILE_NAME" ]]; then
    printf 'ERROR: kubectl context is "%s", expected "%s". Run setup-profile.sh first.\n' "$current_context" "$PROFILE_NAME" >&2
    exit 1
fi

kubectl get ns "$ISTIO_SYSTEM" >/dev/null 2>&1 \
    || { printf 'ERROR: namespace %s not found - run setup-istio.sh first.\n' "$ISTIO_SYSTEM" >&2; exit 1; }

if ! kubectl get deploy otel-lgtm -n "$OBS_NS" >/dev/null 2>&1; then
    printf 'WARNING: %s/otel-lgtm not found. Kiali will install but its metrics integration\n' "$OBS_NS" >&2
    printf 'will be dark until setup-lgtm.sh has run.\n' >&2
fi

# ─── 1. kiali helm repo ───────────────────────────────────────────────────────

step "Ensuring the kiali helm repo is registered"
if helm repo list 2>/dev/null | grep -q '^kiali[[:space:]]'; then
    helm repo update kiali >/dev/null
else
    helm repo add kiali https://kiali.org/helm-charts
    helm repo update kiali >/dev/null
fi

# ─── 2. kiali-operator (cluster-wide, watches for a Kiali CR) ───────────────

step "Installing kiali-operator ${KIALI_VERSION} into ${ISTIO_SYSTEM}"
helm upgrade --install kiali-operator kiali/kiali-operator \
    --namespace "$ISTIO_SYSTEM" \
    --version "$KIALI_VERSION" \
    --set watchNamespace="" \
    --wait --timeout 5m

step "Waiting for the kiali-operator deployment to be Available"
kubectl wait --for=condition=Available --timeout=180s \
    deployment/kiali-operator -n "$ISTIO_SYSTEM"

# ─── 3. Kiali custom resource, wired to otel-lgtm ───────────────────────────

step "Applying the Kiali CR, wired to otel-lgtm"
printf '    prometheus: %s\n' "$PROM_URL"
printf '    grafana:    %s (in-cluster) / %s (browser)\n' "$GRAFANA_IN_URL" "$GRAFANA_EXT_URL"
printf '    tracing:    %s\n' "$TEMPO_URL"

kubectl apply -n "$ISTIO_SYSTEM" -f - <<EOF
apiVersion: kiali.io/v1alpha1
kind: Kiali
metadata:
  name: kiali
  namespace: ${ISTIO_SYSTEM}
spec:
  auth:
    strategy: anonymous
  deployment:
    view_only_mode: false
    service_type: NodePort
  external_services:
    istio:
      root_namespace: ${ISTIO_SYSTEM}
    prometheus:
      url: ${PROM_URL}
    grafana:
      enabled: true
      internal_url: ${GRAFANA_IN_URL}
      external_url: ${GRAFANA_EXT_URL}
    tracing:
      enabled: true
      provider: tempo
      internal_url: ${TEMPO_URL}
      use_grpc: false
EOF

step "Waiting for the kiali Deployment (created by the operator from the CR) to be Available"
for _ in $(seq 1 60); do
    kubectl get deployment kiali -n "$ISTIO_SYSTEM" >/dev/null 2>&1 && break
    sleep 5
done
kubectl rollout status deployment/kiali -n "$ISTIO_SYSTEM" --timeout=300s

# Defensive: some chart/operator versions do not apply deployment.node_port
# to the rendered Service. Pin it directly if it drifted.
current_node_port="$(kubectl get svc kiali -n "$ISTIO_SYSTEM" \
    -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || true)"
if [[ "$current_node_port" != "$NP_KIALI" ]]; then
    printf 'Pinning svc/kiali nodePort to %s (was %s)\n' "$NP_KIALI" "${current_node_port:-unset}"
    kubectl -n "$ISTIO_SYSTEM" patch svc kiali --type=strategic \
        -p "{\"spec\":{\"ports\":[{\"port\":${TP_KIALI},\"nodePort\":${NP_KIALI}}]}}"
fi

# ─── Done ────────────────────────────────────────────────────────────────────

step "Kiali is installed and wired to otel-lgtm."
printf '\nView the mesh topology (NodePort published on 127.0.0.1):\n'
printf '  http://localhost:%s/kiali   (Graph -> namespace: %s)\n' "$TP_KIALI" "$APP_NS"
printf '\nNote: the live traffic graph only shows edges while traffic is flowing - it\n'
printf 'stays quiet until a chapter app (meshed by namespace membership) sends traffic.\n'
