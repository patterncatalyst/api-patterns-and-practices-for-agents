#!/usr/bin/env bash
#
# setup-apicurio.sh - install Apicurio Registry as the schema registry for
# the book's Avro/JSON Kafka contracts, in-memory storage (h2), matching
# examples/_infra/compose.yaml's own dev-scale setup exactly.
#
# Pin (see _plans/versions.md): quay.io/apicurio/apicurio-registry:3.3.4.
# Data does not survive a pod restart; producers/consumers re-register their
# contracts on startup, which is fine for development.
#
# Idempotent: kubectl apply.
#
# Usage:
#   ./setup-apicurio.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

NS="$EP_DATA_NS"
PROFILE_NAME="$EP_PROFILE"
APICURIO_IMAGE="${APICURIO_IMAGE:-quay.io/apicurio/apicurio-registry:3.3.4}"

step() { printf '\n==> %s\n' "$1"; }

command -v kubectl >/dev/null 2>&1 || { printf 'ERROR: kubectl not in PATH.\n' >&2; exit 1; }

current_context="$(kubectl config current-context 2>/dev/null || echo "")"
if [[ "$current_context" != "$PROFILE_NAME" ]]; then
    printf 'ERROR: kubectl context is "%s", expected "%s". Run setup-profile.sh first.\n' "$current_context" "$PROFILE_NAME" >&2
    exit 1
fi

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

step "Installing Apicurio Registry ${APICURIO_IMAGE} into ${NS} (in-memory, v3 API)"
kubectl apply -n "$NS" -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: apicurio
  namespace: ${NS}
  labels:
    app.kubernetes.io/name: apicurio-registry
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: apicurio-registry
  template:
    metadata:
      labels:
        app.kubernetes.io/name: apicurio-registry
    spec:
      containers:
        - name: apicurio
          image: ${APICURIO_IMAGE}
          env:
            - name: APICURIO_STORAGE_KIND
              value: "sql"
            - name: APICURIO_STORAGE_SQL_KIND
              value: "h2"
          ports:
            - name: http
              containerPort: 8080
            - name: management
              containerPort: 9000
          readinessProbe:
            httpGet:
              path: /apis/registry/v3/system/info
              port: http
            initialDelaySeconds: 15
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /health/ready
              port: management
            initialDelaySeconds: 20
            periodSeconds: 15
          resources:
            requests:
              cpu: 100m
              memory: 384Mi
            limits:
              cpu: 500m
              memory: 768Mi
EOF

step "Waiting for Apicurio to roll out"
kubectl rollout status -n "$NS" deploy/apicurio --timeout=300s

step "Publishing the host NodePort"
kubectl apply -f "${SCRIPT_DIR}/overlays/minikube/apicurio-nodeport.yaml"

printf '\n==> Apicurio Registry installed.\n\n'
printf 'In-cluster endpoint: http://apicurio.%s.svc.cluster.local:8080/apis/registry/v3\n' "$NS"
printf 'Host access: http://127.0.0.1:%s/apis/registry/v3 (./show-endpoints.sh)\n' "$TP_APICURIO"
