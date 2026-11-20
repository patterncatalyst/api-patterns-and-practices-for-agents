#!/usr/bin/env bash
#
# bootstrap.sh - stand up the final project's Kubernetes platform from an
# empty minikube profile, tiered and health-gated: each tier must be Ready
# before the next one starts.
#
# HOST RESOURCE ASSUMPTIONS (see setup-profile.sh for the sizing rationale):
#   - Host RAM:  comfortably more than the 16 GiB the profile requests by
#     default (MINIKUBE_MEMORY); leave headroom for the host OS, IDE, browser.
#   - Host CPU:  a laptop-class 8-core CPU or better.
#   - Host disk: ~60 GB free under the Docker data root for the node, the
#     image cache and growing PVs (PVCs total ~30 GiB across Postgres, Kafka
#     and the Ollama model volume).
#   - Toolchain: minikube (--driver=docker), kubectl, helm, docker.
#
# Tiers, each gated on health before the next (the order the task's owner
# approved: operator before its CR, data services before observability,
# observability before identity/schema, the mesh after everything it will
# observe, Ollama last since it is the slowest to become useful):
#   1. minikube profile + namespaces
#   2. CloudNativePG operator + Postgres cluster CR
#   3. Strimzi operator + Kafka cluster CR
#   4. otel-lgtm (observability)
#   5. Keycloak
#   6. Apicurio
#   7. Istio (mesh + STRICT mTLS on the apa namespace)
#   8. Kiali (operator + CR, wired to otel-lgtm)
#   9. Ollama (+ model pre-pull Job)
#
# Idempotent: every setup-*.sh script here uses helm upgrade --install,
# kubectl apply and kubectl wait/rollout status - re-running after an
# interrupted run resumes safely.
#
# Usage (from the project root or from examples/_k8s/):
#   ./bootstrap.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || exit 1
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

PROFILE="$EP_PROFILE"
APP_NS="apa"
DATA_NS="$EP_DATA_NS"
OBS_NS="$EP_OBS_NS"

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
ok()   { printf '    \xe2\x9c\x93 %s\n' "$1"; }
fail() { printf '\n\xe2\x9c\x97 %s\n' "$1" >&2; exit 1; }

printf '\n\033[1m==> Active configuration\033[0m\n'
printf '    profile:    %s\n' "$PROFILE"
printf '    app ns:     %s\n' "$APP_NS"
printf '    data ns:    %s\n' "$DATA_NS"
printf '    obs ns:     %s\n' "$OBS_NS"

# ─── Tier 1: profile + namespaces ───────────────────────────────────────────
step "1/9 minikube profile + namespaces"
./setup-profile.sh || fail "profile setup failed"
[[ "$(kubectl config current-context 2>/dev/null)" == "$PROFILE" ]] || kubectl config use-context "$PROFILE"
kubectl create namespace "$DATA_NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl create namespace "$OBS_NS"  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -k base >/dev/null
ok "profile up, context set, namespaces exist, base ConfigMap applied"

# ─── Tier 2: CloudNativePG operator + Postgres cluster CR ───────────────────
step "2/9 CloudNativePG operator + Postgres cluster CR"
./setup-postgres-operator.sh || fail "postgres-operator/CR setup failed"
ok "CNPG operator installed, Postgres primary Ready"

# ─── Tier 3: Strimzi operator + Kafka cluster CR ────────────────────────────
step "3/9 Kafka (Strimzi operator + cluster CR)"
./setup-kafka-operator.sh || fail "kafka-operator setup failed"
ok "Kafka cluster Ready"

# ─── Tier 4: otel-lgtm observability ─────────────────────────────────────────
step "4/9 otel-lgtm observability image"
./setup-lgtm.sh || fail "otel-lgtm setup failed"
ok "otel-lgtm installed (namespace: ${OBS_NS})"

# ─── Tier 5: Keycloak ────────────────────────────────────────────────────────
step "5/9 Keycloak (identity provider, realm apa imported)"
./setup-keycloak.sh || fail "keycloak setup failed"
ok "Keycloak installed"

# ─── Tier 6: Apicurio ────────────────────────────────────────────────────────
step "6/9 Apicurio schema registry"
./setup-apicurio.sh || fail "apicurio setup failed"
ok "Apicurio installed"

# ─── Tier 7: Istio ───────────────────────────────────────────────────────────
step "7/9 Istio control plane (mesh + STRICT mTLS on ${APP_NS})"
./setup-istio.sh || fail "istio setup failed"
kubectl wait -n istio-system --for=condition=Available deploy/istiod --timeout=180s || fail "istiod not Available"
ok "istiod Available"

# ─── Tier 8: Kiali ───────────────────────────────────────────────────────────
step "8/9 Kiali (operator + CR, wired to otel-lgtm)"
./setup-kiali.sh || fail "kiali setup failed"
ok "Kiali installed"

# ─── Tier 9: Ollama ──────────────────────────────────────────────────────────
step "9/9 Ollama + model pre-pull"
./setup-ollama.sh || fail "ollama setup failed"
ok "Ollama installed and model pulled"

# ─── Platform NodePort Services (idempotent, belt-and-suspenders) ───────────
step "Publishing platform NodePort Services"
kubectl apply -k overlays/minikube || fail "overlays/minikube apply failed"

# ─── Done ────────────────────────────────────────────────────────────────────
step "Bring-up complete."
cat <<EOF

    Chapter app images:    ./load-images.sh      (build + load into the profile, once a chapter ships one)
    Host access:           NodePorts published on 127.0.0.1 (./show-endpoints.sh)
    Tear down the profile: ./teardown.sh

EOF
./show-endpoints.sh || fail "show-endpoints.sh reported a problem: a port is unpublished or the cluster is not reachable"
printf '\n'
