#!/usr/bin/env bash
#
# setup-ollama.sh - install Ollama and pre-pull the book's pinned model onto
# a PVC with a plain Job (not a Helm hook), so the model survives pod
# restarts without re-downloading.
#
# Pins (see _plans/versions.md):
#   docker.io/ollama/ollama:0.40.16@sha256:a372a1c0d1e15b68e9521189785c3ca17795c26c2356f8b1b10122cfb59ed0d7
#   qwen3:4b-instruct-2507-q4_K_M
#
# The model-pull Job runs the SAME image as a thin client: `ollama pull`
# sends the pull request to a remote server named by OLLAMA_HOST, so the
# model lands in the SERVER's own storage (the Deployment's PVC) - the Job's
# own pod needs no volume mount.
#
# Idempotent: kubectl apply; the pull Job is only (re)created if the model is
# not already present on the server, so re-running this script after the
# model has landed is a fast no-op.
#
# Usage:
#   ./setup-ollama.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

NS="$EP_DATA_NS"
PROFILE_NAME="$EP_PROFILE"
OLLAMA_IMAGE="${OLLAMA_IMAGE:-docker.io/ollama/ollama:0.40.16@sha256:a372a1c0d1e15b68e9521189785c3ca17795c26c2356f8b1b10122cfb59ed0d7}"
MODEL="${OLLAMA_MODEL:-qwen3:4b-instruct-2507-q4_K_M}"
OLLAMA_HOST="http://ollama.${NS}.svc.cluster.local:11434"

step() { printf '\n==> %s\n' "$1"; }

command -v kubectl >/dev/null 2>&1 || { printf 'ERROR: kubectl not in PATH.\n' >&2; exit 1; }

current_context="$(kubectl config current-context 2>/dev/null || echo "")"
if [[ "$current_context" != "$PROFILE_NAME" ]]; then
    printf 'ERROR: kubectl context is "%s", expected "%s". Run setup-profile.sh first.\n' "$current_context" "$PROFILE_NAME" >&2
    exit 1
fi

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

step "Applying the Ollama model PVC"
kubectl apply -n "$NS" -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: ollama-models
  namespace: ${NS}
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: 10Gi
EOF

step "Installing Ollama ${OLLAMA_IMAGE} into ${NS}"
kubectl apply -n "$NS" -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ollama
  namespace: ${NS}
  labels:
    app.kubernetes.io/name: ollama
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app.kubernetes.io/name: ollama
  template:
    metadata:
      labels:
        app.kubernetes.io/name: ollama
    spec:
      containers:
        - name: ollama
          image: ${OLLAMA_IMAGE}
          env:
            - name: OLLAMA_NUM_PARALLEL
              value: "1"
            - name: OLLAMA_MAX_LOADED_MODELS
              value: "1"
            - name: OLLAMA_KEEP_ALIVE
              value: "30m"
            - name: OLLAMA_CONTEXT_LENGTH
              value: "2048"
          ports:
            - name: http
              containerPort: 11434
          volumeMounts:
            - name: models
              mountPath: /root/.ollama
          # The image has no curl, wget or shell utility suite beyond its own
          # binary: use the CLI itself as the probe, exactly like
          # examples/_infra/compose.yaml's healthcheck.
          readinessProbe:
            exec:
              command: ["ollama", "list"]
            initialDelaySeconds: 5
            periodSeconds: 10
            failureThreshold: 6
          livenessProbe:
            exec:
              command: ["ollama", "list"]
            initialDelaySeconds: 15
            periodSeconds: 20
            failureThreshold: 6
          resources:
            requests:
              cpu: 500m
              memory: 2Gi
            limits:
              cpu: "2"
              memory: 5Gi
      volumes:
        - name: models
          persistentVolumeClaim:
            claimName: ollama-models
EOF

step "Waiting for Ollama to roll out"
kubectl rollout status -n "$NS" deploy/ollama --timeout=300s

step "Publishing the host NodePort"
kubectl apply -f "${SCRIPT_DIR}/overlays/minikube/ollama-nodeport.yaml"

step "Pre-pulling ${MODEL} (skipped if already present)"
already_present="$(kubectl exec -n "$NS" deploy/ollama -- ollama list 2>/dev/null \
    | grep -c "^${MODEL}" || true)"
if [[ "${already_present:-0}" -gt 0 ]]; then
    printf '%s is already pulled; skipping the Job.\n' "$MODEL"
else
    kubectl delete job ollama-model-pull -n "$NS" --ignore-not-found >/dev/null
    kubectl apply -n "$NS" -f - <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: ollama-model-pull
  namespace: ${NS}
spec:
  backoffLimit: 3
  template:
    spec:
      restartPolicy: OnFailure
      containers:
        - name: pull
          image: ${OLLAMA_IMAGE}
          env:
            - name: OLLAMA_HOST
              value: "${OLLAMA_HOST}"
          command: ["ollama", "pull", "${MODEL}"]
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
EOF
    step "Waiting for the model pull to complete (a multi-GB download can take a while)"
    kubectl wait --for=condition=complete job/ollama-model-pull -n "$NS" --timeout=1200s
fi

printf '\n==> Ollama installed, model %s ready.\n\n' "$MODEL"
printf 'In-cluster endpoint: %s\n' "$OLLAMA_HOST"
printf 'Host access: http://127.0.0.1:%s/api/tags (./show-endpoints.sh)\n' "$TP_OLLAMA"
