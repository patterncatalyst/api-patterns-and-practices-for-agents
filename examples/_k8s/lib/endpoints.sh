#!/usr/bin/env bash
#
# lib/endpoints.sh - the one source of truth for host access to the apa
# minikube profile.
#
# Every host-facing platform service is a fixed NodePort. The minikube
# profile publishes each NodePort to the host on loopback when the profile
# is created:
#   minikube start -p apa --ports=127.0.0.1:<hostPort>:<nodePort>,...
# so http://127.0.0.1:<hostPort> reaches the Service directly. Nothing else
# runs on the host and nothing needs to be started per chapter.
#
# Source this from a setup script or show-endpoints.sh:
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/endpoints.sh"
#
# The canonical host-port <-> nodePort map lives here and nowhere else. A new
# platform endpoint gets one row in _endpoint_row, its name added to
# ENDPOINT_NAMES, and a matching Service nodePort in the setup script or
# overlays/minikube manifest that creates it. Published ports are fixed at
# profile creation: changing the map means recreating the profile
# (setup-profile.sh --replace), which deletes and recreates the cluster, so
# bootstrap.sh runs again afterwards.
#
# Safe to source under `set -euo pipefail`: definitions only, no side effects.

EP_PROFILE="${MINIKUBE_PROFILE:-apa}"
EP_DATA_NS="${APA_DATA_NAMESPACE:-apa-data}"
EP_OBS_NS="${OBS_NAMESPACE:-observability}"
EP_ISTIO_NS="istio-system"

# TP_* is the host port published on 127.0.0.1 (what you curl or point a
# client at); NP_* is the NodePort the Service exposes.
TP_GRAFANA=3000;     NP_GRAFANA=30300
TP_OTLP_GRPC=4317;   NP_OTLP_GRPC=30317
TP_OTLP_HTTP=4318;   NP_OTLP_HTTP=30318
TP_LOKI=3100;        NP_LOKI=30310
TP_TEMPO=3200;       NP_TEMPO=30320
TP_PROMETHEUS=19090; NP_PROMETHEUS=30909
TP_KAFKA=9092;       NP_KAFKA=30920
TP_POSTGRES=5432;    NP_POSTGRES=30543
TP_APICURIO=8081;    NP_APICURIO=30808
TP_KEYCLOAK=8180;    NP_KEYCLOAK=30818
TP_OLLAMA=11434;     NP_OLLAMA=31143
TP_KIALI=20001;      NP_KIALI=30201

ENDPOINT_NAMES=(grafana otlp-grpc otlp-http loki tempo prometheus kafka postgres apicurio keycloak ollama kiali)

# name -> "hostPort nodePort namespace service label [path]"
# (service/namespace used for a live Service check; path is display-only)
_endpoint_row() {
    case "$1" in
        grafana)    echo "$TP_GRAFANA $NP_GRAFANA $EP_OBS_NS otel-lgtm Grafana" ;;
        otlp-grpc)  echo "$TP_OTLP_GRPC $NP_OTLP_GRPC $EP_OBS_NS otel-lgtm OTLP-gRPC" ;;
        otlp-http)  echo "$TP_OTLP_HTTP $NP_OTLP_HTTP $EP_OBS_NS otel-lgtm OTLP-HTTP" ;;
        loki)       echo "$TP_LOKI $NP_LOKI $EP_OBS_NS otel-lgtm Loki" ;;
        tempo)      echo "$TP_TEMPO $NP_TEMPO $EP_OBS_NS otel-lgtm Tempo" ;;
        prometheus) echo "$TP_PROMETHEUS $NP_PROMETHEUS $EP_OBS_NS otel-lgtm Prometheus" ;;
        # Strimzi owns this Service (<cluster>-kafka-external-bootstrap); see
        # setup-kafka-operator.sh's "external" nodeport listener.
        kafka)      echo "$TP_KAFKA $NP_KAFKA $EP_DATA_NS apa-kafka-external-bootstrap Kafka" ;;
        postgres)   echo "$TP_POSTGRES $NP_POSTGRES $EP_DATA_NS apa-postgres-nodeport PostgreSQL" ;;
        apicurio)   echo "$TP_APICURIO $NP_APICURIO $EP_DATA_NS apicurio Apicurio /apis/registry/v3" ;;
        keycloak)   echo "$TP_KEYCLOAK $NP_KEYCLOAK $EP_DATA_NS keycloak Keycloak /realms/apa" ;;
        ollama)     echo "$TP_OLLAMA $NP_OLLAMA $EP_DATA_NS ollama Ollama /api/tags" ;;
        # The kiali-operator CR ("kiali", kind Kiali) renders the Service.
        kiali)      echo "$TP_KIALI $NP_KIALI $EP_ISTIO_NS kiali Kiali /kiali" ;;
        *) return 1 ;;
    esac
}

# endpoint_url <name> -> http://127.0.0.1:<hostPort>  (no display path)
endpoint_url() {
    local row hp
    row="$(_endpoint_row "$1")" || return 1
    read -r hp _ <<<"$row"
    echo "http://127.0.0.1:${hp}"
}

# node_ports_arg -> "127.0.0.1:<host>:<node>,..." for `minikube start --ports=`.
node_ports_arg() {
    local name row hp np out=()
    for name in "${ENDPOINT_NAMES[@]}"; do
        row="$(_endpoint_row "$name")"
        read -r hp np _ <<<"$row"
        out+=("127.0.0.1:${hp}:${np}")
    done
    local IFS=','
    echo "${out[*]}"
}

# docker_engine_ok -> 0 if the Docker Engine answers; else a hint on stderr, 1
docker_engine_ok() {
    docker info >/dev/null 2>&1 && return 0
    printf 'Docker Engine is not reachable. Start it and make sure your user is in the docker group.\n' >&2
    return 1
}

# profile_container_exists / profile_container_running -> the profile's node
# container (docker driver names it after the profile).
profile_container_exists() { docker container inspect "$EP_PROFILE" >/dev/null 2>&1; }
profile_container_running() {
    [[ "$(docker container inspect -f '{{.State.Running}}' "$EP_PROFILE" 2>/dev/null)" == "true" ]]
}

# assert_host_ports_free [<own-host-ports>] - every host port in the map must
# be free on the host. <own-host-ports> is a space-separated list of ports to
# skip (ports this same profile currently publishes while it is RUNNING).
# Prints one error per busy port on stderr; returns 1 if any is busy.
assert_host_ports_free() {
    local own=" ${1:-} " spec hp busy=0
    local -a specs
    if ! command -v ss >/dev/null 2>&1; then
        printf 'ERROR: ss not in PATH (iproute2); needed to check host ports are free.\n' >&2
        return 1
    fi
    IFS=',' read -ra specs <<<"$(node_ports_arg)"
    for spec in "${specs[@]}"; do
        hp="$(cut -d: -f2 <<<"$spec")"
        [[ "$own" == *" $hp "* ]] && continue
        if [[ -n "$(ss -Htln "sport = :$hp" 2>/dev/null)" ]]; then
            printf 'ERROR: host port %s is already in use. Stop whatever else is listening on it first.\n' "$hp" >&2
            busy=1
        fi
    done
    return "$busy"
}

# published_ports -> lines "<nodePort> <hostPort>" bound on 127.0.0.1
published_ports() {
    command -v python3 >/dev/null 2>&1 || return 1
    docker container inspect --format '{{json .HostConfig.PortBindings}}' "$EP_PROFILE" 2>/dev/null \
        | python3 -c '
import json, sys
try:
    data = json.loads(sys.stdin.read() or "null") or {}
except Exception:
    sys.exit(0)
for key, binds in sorted(data.items()):
    port = key.split("/")[0]
    for b in binds or []:
        if b.get("HostIp") == "127.0.0.1" and b.get("HostPort"):
            print(port, b["HostPort"])
'
}

# check_published_ports - verify every required pair is published on
# loopback. Returns 1 with a hint on any gap.
check_published_ports() {
    local pub name row hp np msg=""
    command -v python3 >/dev/null 2>&1 || { printf 'ERROR: python3 is required.\n' >&2; return 1; }
    pub="$(published_ports)"
    for name in "${ENDPOINT_NAMES[@]}"; do
        row="$(_endpoint_row "$name")"
        read -r hp np _ <<<"$row"
        grep -qx "${np} ${hp}" <<<"$pub" || msg+="  missing: 127.0.0.1:${hp} -> nodePort ${np} (${name})"$'\n'
    done
    [[ -z "$msg" ]] && return 0
    {
        printf 'endpoints: profile "%s" does not publish the required ports:\n' "$EP_PROFILE"
        printf '%s' "$msg"
        printf 'Ports are fixed when the profile is created. Recreate with: ./setup-profile.sh --replace\n'
    } >&2
    return 1
}
