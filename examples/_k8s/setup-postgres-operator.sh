#!/usr/bin/env bash
#
# setup-postgres-operator.sh - install the CloudNativePG operator (cluster-
# wide, via Helm) and apply a single-instance Postgres Cluster CR (raw
# manifest - Helm is reserved for operators) into the apa-data namespace,
# then publish a host NodePort for it.
#
# Pins (see _plans/versions.md): CloudNativePG operator v1.30.1, operand
# ghcr.io/cloudnative-pg/postgresql:18.6-202611090812-standard-trixie (the
# same tag examples/_infra/compose.yaml uses), timezone UTC.
#
# This namespace (apa-data) is deliberately NOT labeled for Istio sidecar
# injection (see setup-istio.sh): the operator's own TLS handshake to the
# primary would collide with a sidecar's mTLS wrapping. Only the apa
# namespace (chapter apps, landing in a later wave) is meshed.
#
# Idempotent: re-running upgrades the operator in place and re-applies the CR.
#
# Usage:
#   ./setup-postgres-operator.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

NS="$EP_DATA_NS"
PROFILE_NAME="$EP_PROFILE"
OPERATOR_NS="cnpg-system"
CHART_VERSION="${CNPG_CHART_VERSION:-0.29.1}"   # operator 1.30.1
PG_IMAGE="${PG_IMAGE:-ghcr.io/cloudnative-pg/postgresql:18.6-202611090812-standard-trixie}"
RELEASE_NAME="cnpg"
CLUSTER_NAME="apa-postgres"
PG_DATABASE="apa"
PG_OWNER="apa"

step() { printf '\n==> %s\n' "$1"; }

command -v helm    >/dev/null 2>&1 || { printf 'ERROR: helm not in PATH.\n' >&2; exit 1; }
command -v kubectl >/dev/null 2>&1 || { printf 'ERROR: kubectl not in PATH.\n' >&2; exit 1; }

current_context=$(kubectl config current-context 2>/dev/null || echo "")
if [[ "$current_context" != "$PROFILE_NAME" ]]; then
    printf 'ERROR: kubectl context is "%s", expected "%s". Run setup-profile.sh first.\n' "$current_context" "$PROFILE_NAME" >&2
    exit 1
fi

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# ─── 1. Install the operator (cluster-wide) ──────────────────────────────────

step "Adding the CloudNativePG helm repository"
helm repo add cnpg https://cloudnative-pg.github.io/charts >/dev/null 2>&1 || true
helm repo update cnpg >/dev/null

step "Installing CloudNativePG operator ${CHART_VERSION} (cluster-wide) into ${OPERATOR_NS}"
helm upgrade --install "$RELEASE_NAME" cnpg/cloudnative-pg \
    --namespace "$OPERATOR_NS" \
    --create-namespace \
    --version "$CHART_VERSION" \
    --wait \
    --timeout 5m

step "Waiting for the operator deployment to be Available"
kubectl wait --for=condition=Available --timeout=180s \
    deployment/cnpg-cloudnative-pg \
    -n "$OPERATOR_NS"

step "Verifying CRDs are registered (cluster-scoped)"
kubectl get crd | grep -E 'cnpg\.io' || {
    printf 'ERROR: CloudNativePG CRDs not found after install.\n' >&2
    exit 1
}

# ─── 2. Apply the Cluster CR ─────────────────────────────────────────────────

step "Applying Postgres Cluster CR '${CLUSTER_NAME}' into '${NS}' (1 instance, TZ=UTC)"
kubectl apply -n "$NS" -f - <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: ${CLUSTER_NAME}
  namespace: ${NS}
spec:
  instances: 1
  imageName: ${PG_IMAGE}
  storage:
    size: 5Gi
  postgresql:
    parameters:
      timezone: "UTC"
  bootstrap:
    initdb:
      database: ${PG_DATABASE}
      owner: ${PG_OWNER}
EOF

step "Waiting for the Postgres primary to be Ready (can take a couple minutes)"
pg_ready=0
for _ in $(seq 1 72); do
    status="$(kubectl get pods -n "$NS" -l "cnpg.io/cluster=${CLUSTER_NAME},cnpg.io/instanceRole=primary" \
        -o jsonpath='{.items[0].status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
    if [[ "$status" == "True" ]]; then
        pg_ready=1
        break
    fi
    sleep 5
done
if (( ! pg_ready )); then
    printf 'ERROR: Postgres primary did not become Ready in time.\n' >&2
    exit 1
fi
step "Postgres primary is Ready."

# ─── 3. Replicate the app credentials into the application namespace ─────────
# CloudNativePG creates the `<cluster>-app` credentials Secret in its own
# namespace (${NS}), but the meshed chapter apps run in the `apa` namespace and
# Secrets are namespace-scoped, so a Deployment there cannot read it. Copy it
# across (keys unchanged: username/password/uri/...), stripping the server-set
# metadata so `kubectl apply` is clean and idempotent. The values travel inside
# the JSON stream, never on a command line, so nothing leaks to the process
# table.
APP_NS="${APP_NS:-apa}"
step "Replicating ${CLUSTER_NAME}-app credentials into '${APP_NS}'"
kubectl get secret "${CLUSTER_NAME}-app" -n "$NS" -o json \
    | jq 'del(.metadata.namespace, .metadata.resourceVersion, .metadata.uid, .metadata.creationTimestamp, .metadata.ownerReferences, .metadata.managedFields)' \
    | kubectl apply -n "$APP_NS" -f -

# ─── 4. Host NodePort (overlays/minikube owns the manifest) ─────────────────

step "Publishing the Postgres host NodePort"
kubectl apply -f "${SCRIPT_DIR}/overlays/minikube/postgres-nodeport.yaml"

printf '\nConnection (in-cluster): %s-rw.%s.svc.cluster.local:5432/%s (owner: %s)\n' \
    "$CLUSTER_NAME" "$NS" "$PG_DATABASE" "$PG_OWNER"
printf 'Password: kubectl get secret -n %s %s-app -o jsonpath="{.data.password}" | base64 -d\n' "$NS" "$CLUSTER_NAME"
printf 'Host access: 127.0.0.1:%s (./show-endpoints.sh)\n' "$TP_POSTGRES"
