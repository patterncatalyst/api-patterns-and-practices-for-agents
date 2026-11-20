#!/usr/bin/env bash
#
# setup-keycloak.sh - install Keycloak as the book-wide identity provider,
# importing the SAME realm the compose stack uses
# (examples/_infra/keycloak/realm-apa.json) via a ConfigMap, so a K8s app and
# a compose app validate tokens against the identical "apa" realm.
#
# Pin (see _plans/versions.md): quay.io/keycloak/keycloak:26.8.2. Dev mode
# (start-dev --import-realm), matching the compose service exactly.
#
# Idempotent: the ConfigMap is re-created from the current file on every run,
# and kubectl apply is idempotent for the Deployment.
#
# Usage:
#   ./setup-keycloak.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

NS="$EP_DATA_NS"
PROFILE_NAME="$EP_PROFILE"
KEYCLOAK_IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.8.2}"
REALM_FILE="${SCRIPT_DIR}/../_infra/keycloak/realm-apa.json"

step() { printf '\n==> %s\n' "$1"; }

command -v kubectl >/dev/null 2>&1 || { printf 'ERROR: kubectl not in PATH.\n' >&2; exit 1; }

current_context="$(kubectl config current-context 2>/dev/null || echo "")"
if [[ "$current_context" != "$PROFILE_NAME" ]]; then
    printf 'ERROR: kubectl context is "%s", expected "%s". Run setup-profile.sh first.\n' "$current_context" "$PROFILE_NAME" >&2
    exit 1
fi
[[ -f "$REALM_FILE" ]] || { printf 'ERROR: realm file not found at %s\n' "$REALM_FILE" >&2; exit 1; }

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

step "Importing realm-apa.json into a ConfigMap"
kubectl create configmap apa-realm \
    --from-file="realm-apa.json=${REALM_FILE}" \
    -n "$NS" --dry-run=client -o yaml | kubectl apply -f -

step "Installing Keycloak ${KEYCLOAK_IMAGE} into ${NS}"
kubectl apply -n "$NS" -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keycloak
  namespace: ${NS}
  labels:
    app.kubernetes.io/name: keycloak
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: keycloak
  template:
    metadata:
      labels:
        app.kubernetes.io/name: keycloak
    spec:
      containers:
        - name: keycloak
          image: ${KEYCLOAK_IMAGE}
          args: ["start-dev", "--import-realm"]
          env:
            - name: KC_BOOTSTRAP_ADMIN_USERNAME
              value: "admin"
            - name: KC_BOOTSTRAP_ADMIN_PASSWORD
              value: "admin"
            - name: KC_HEALTH_ENABLED
              value: "true"
          ports:
            - name: http
              containerPort: 8080
            - name: management
              containerPort: 9000
          volumeMounts:
            - name: realm-import
              mountPath: /opt/keycloak/data/import/realm-apa.json
              subPath: realm-apa.json
              readOnly: true
          startupProbe:
            httpGet:
              path: /health/ready
              port: management
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 60
          readinessProbe:
            httpGet:
              path: /health/ready
              port: management
            periodSeconds: 10
            failureThreshold: 3
          livenessProbe:
            httpGet:
              path: /health/live
              port: management
            periodSeconds: 15
            failureThreshold: 3
          resources:
            requests:
              cpu: 250m
              memory: 512Mi
            limits:
              cpu: 1000m
              memory: 1Gi
      volumes:
        - name: realm-import
          configMap:
            name: apa-realm
EOF

step "Waiting for Keycloak to roll out (realm import runs before the health port answers)"
kubectl rollout status -n "$NS" deploy/keycloak --timeout=360s

step "Publishing the host NodePort"
kubectl apply -f "${SCRIPT_DIR}/overlays/minikube/keycloak-nodeport.yaml"

printf '\n==> Keycloak installed in the %s namespace, realm "apa" imported.\n\n' "$NS"
printf 'In-cluster issuer: http://keycloak.%s.svc.cluster.local:8080/realms/apa\n' "$NS"
printf 'Host access: http://127.0.0.1:%s/realms/apa (./show-endpoints.sh)\n' "$TP_KEYCLOAK"
