#!/usr/bin/env bash
#
# setup-kafka-operator.sh - install the Strimzi Kafka operator (Helm,
# namespace-scoped into apa-data) and apply a single-node KRaft Kafka
# cluster CR with a nodeport listener for host access (kcat and friends).
#
# Pins (see _plans/versions.md): Strimzi operator 1.3.1, Kafka 4.3.2, KRaft
# only (KafkaNodePool, no ZooKeeper, no entity operator - this platform does
# not need topic/user management beyond what the apps create themselves).
# Strimzi 1.x serves only the kafka.strimzi.io/v1 API.
#
# The "external" listener uses Strimzi's own nodeport listener type (the
# operator creates and owns the NodePort Service), pinned to the fixed
# nodePort this platform publishes on the host, with the advertised
# host/port set to the SAME host:port so a client that completes the
# Kafka bootstrap handshake reconnects to an address it can actually reach
# instead of the pod's internal address.
#
# Idempotent: re-running upgrades the operator in place and re-applies the CR.
#
# Usage:
#   ./setup-kafka-operator.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

NS="$EP_DATA_NS"
PROFILE_NAME="$EP_PROFILE"
STRIMZI_VERSION="${STRIMZI_VERSION:-1.3.1}"
KAFKA_VERSION="${KAFKA_VERSION:-4.3.2}"
CLUSTER_NAME="apa"
# The nodeport listener's per-broker nodePort. Only the bootstrap nodePort
# (below) is published on the host; the broker nodePort below is reachable
# only inside the cluster, which is fine because advertisedPort tells every
# client (bootstrap and broker alike) to reconnect to the published
# bootstrap host:port.
BROKER_NODE_PORT=30921

step() { printf '\n==> %s\n' "$1"; }

command -v kubectl >/dev/null 2>&1 || { printf 'ERROR: kubectl not in PATH.\n' >&2; exit 1; }
command -v helm    >/dev/null 2>&1 || { printf 'ERROR: helm not in PATH.\n' >&2; exit 1; }

current_context="$(kubectl config current-context 2>/dev/null || echo "")"
if [[ "$current_context" != "$PROFILE_NAME" ]]; then
    printf 'ERROR: kubectl context is "%s", expected "%s". Run setup-profile.sh first.\n' "$current_context" "$PROFILE_NAME" >&2
    exit 1
fi

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# ─── 1. Strimzi cluster operator (namespace-scoped) ─────────────────────────

step "Installing the Strimzi cluster operator ${STRIMZI_VERSION} into '${NS}' (Helm)"
helm upgrade --install strimzi-cluster-operator \
    oci://quay.io/strimzi-helm/strimzi-kafka-operator \
    --version "$STRIMZI_VERSION" \
    --namespace "$NS" --create-namespace

step "Waiting for the operator to be Available"
kubectl wait --for=condition=Available deployment -n "$NS" \
    -l strimzi.io/kind=cluster-operator --timeout=180s \
    || kubectl rollout status deployment/strimzi-cluster-operator -n "$NS" --timeout=180s

step "Strimzi operator ready"
kubectl get crd | grep -i kafka.strimzi.io | head

# ─── 2. Kafka cluster CR (single-node KRaft, nodeport listener) ─────────────

step "Applying the ${CLUSTER_NAME} Kafka cluster CR (single-node KRaft) into '${NS}'"
kubectl apply -n "$NS" -f - <<EOF
apiVersion: kafka.strimzi.io/v1
kind: KafkaNodePool
metadata:
  name: dual-role
  namespace: ${NS}
  labels:
    strimzi.io/cluster: ${CLUSTER_NAME}
spec:
  replicas: 1
  roles:
    - controller
    - broker
  storage:
    type: jbod
    volumes:
      - id: 0
        type: persistent-claim
        size: 10Gi
        deleteClaim: true
---
apiVersion: kafka.strimzi.io/v1
kind: Kafka
metadata:
  name: ${CLUSTER_NAME}
  namespace: ${NS}
spec:
  kafka:
    version: "${KAFKA_VERSION}"
    metadataVersion: "${KAFKA_VERSION%.*}"
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
      - name: tls
        port: 9093
        type: internal
        tls: true
      - name: external
        port: 9094
        type: nodeport
        tls: false
        configuration:
          bootstrap:
            nodePort: ${NP_KAFKA}
          brokers:
            - broker: 0
              nodePort: ${BROKER_NODE_PORT}
              advertisedHost: "127.0.0.1"
              advertisedPort: ${TP_KAFKA}
    config:
      offsets.topic.replication.factor: 1
      transaction.state.log.replication.factor: 1
      transaction.state.log.min.isr: 1
      default.replication.factor: 1
      min.insync.replicas: 1
EOF

step "Waiting for Kafka cluster '${CLUSTER_NAME}' to be Ready (can take a few minutes)"
kubectl wait "kafka/${CLUSTER_NAME}" -n "$NS" --for=condition=Ready --timeout=360s

step "Kafka cluster is Ready."
printf '\nBootstrap servers (in-cluster, plain): %s-kafka-bootstrap.%s.svc.cluster.local:9092\n' "$CLUSTER_NAME" "$NS"
printf 'Host access (external listener): 127.0.0.1:%s (./show-endpoints.sh)\n' "$TP_KAFKA"
printf 'Apicurio (schema registry) is applied separately by setup-apicurio.sh.\n'
