#!/usr/bin/env bash
#
# setup-lgtm.sh - install the observability backend as ONE Deployment and ONE
# Service: the grafana/otel-lgtm image, which bundles Grafana, Loki, Tempo,
# Prometheus and an OTLP receiver in a single container. This is the biggest
# divergence from the sibling datamesh-reference-arch-quarkus platform (which
# installs the separate Loki/Grafana/Tempo/Mimir/Collector Helm charts): this
# book's own compose stack (examples/_infra/compose.lgtm.yaml) already uses
# the single image, so the Kubernetes platform mirrors it instead of adding a
# second, heavier observability shape.
#
# Pin (see _plans/versions.md): docker.io/grafana/otel-lgtm:0.41.0.
#
# Apps send OTLP to otel-lgtm.<namespace>.svc.cluster.local on 4317 (gRPC) or
# 4318 (HTTP) - see base/config.yaml's QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT.
#
# Idempotent: kubectl apply everywhere.
#
# Usage:
#   ./setup-lgtm.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

NS="$EP_OBS_NS"
PROFILE_NAME="$EP_PROFILE"
LGTM_IMAGE="${LGTM_IMAGE:-docker.io/grafana/otel-lgtm:0.41.0}"

step() { printf '\n==> %s\n' "$1"; }

command -v kubectl >/dev/null 2>&1 || { printf 'ERROR: kubectl not in PATH.\n' >&2; exit 1; }

current_context="$(kubectl config current-context 2>/dev/null || echo "")"
if [[ "$current_context" != "$PROFILE_NAME" ]]; then
    printf 'ERROR: kubectl context is "%s", expected "%s". Run setup-profile.sh first.\n' "$current_context" "$PROFILE_NAME" >&2
    exit 1
fi

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

step "Installing otel-lgtm ${LGTM_IMAGE} into ${NS}"
kubectl apply -n "$NS" -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: otel-lgtm
  namespace: ${NS}
  labels:
    app.kubernetes.io/name: otel-lgtm
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: otel-lgtm
  template:
    metadata:
      labels:
        app.kubernetes.io/name: otel-lgtm
    spec:
      containers:
        - name: otel-lgtm
          image: ${LGTM_IMAGE}
          env:
            - name: GF_AUTH_ANONYMOUS_ENABLED
              value: "true"
            - name: GF_AUTH_ANONYMOUS_ORG_ROLE
              value: "Admin"
            - name: GF_AUTH_DISABLE_LOGIN_FORM
              value: "true"
          ports:
            - name: grafana
              containerPort: 3000
            - name: otlp-grpc
              containerPort: 4317
            - name: otlp-http
              containerPort: 4318
            - name: loki
              containerPort: 3100
            - name: tempo
              containerPort: 3200
            - name: prometheus
              containerPort: 9090
          readinessProbe:
            httpGet:
              path: /api/health
              port: grafana
            initialDelaySeconds: 20
            periodSeconds: 10
            failureThreshold: 18
          livenessProbe:
            httpGet:
              path: /api/health
              port: grafana
            initialDelaySeconds: 30
            periodSeconds: 15
            failureThreshold: 6
          resources:
            requests:
              cpu: 250m
              memory: 768Mi
            limits:
              cpu: 1500m
              memory: 2Gi
EOF

step "Waiting for otel-lgtm to roll out"
kubectl rollout status -n "$NS" deploy/otel-lgtm --timeout=300s

step "Publishing the host NodePorts"
kubectl apply -f "${SCRIPT_DIR}/overlays/minikube/otel-lgtm-nodeport.yaml"

printf '\n==> otel-lgtm installed in the %s namespace.\n\n' "$NS"
printf 'In-cluster OTLP endpoint: http://otel-lgtm.%s.svc.cluster.local:4318 (HTTP), otel-lgtm.%s.svc.cluster.local:4317 (gRPC)\n' "$NS" "$NS"
printf 'Host access (NodePorts; ./show-endpoints.sh): Grafana %s, Loki %s, Tempo %s, Prometheus %s\n' \
    "$TP_GRAFANA" "$TP_LOKI" "$TP_TEMPO" "$TP_PROMETHEUS"
