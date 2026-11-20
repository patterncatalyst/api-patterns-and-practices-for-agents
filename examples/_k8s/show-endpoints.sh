#!/usr/bin/env bash
#
# show-endpoints.sh - read-only report of host access to the apa cluster.
#
# For each endpoint: URL, whether the profile publishes its port on
# 127.0.0.1, whether the Service nodePort matches, and whether something
# answers. A Service that is absent is reported "not deployed" (expected
# before the relevant setup script, or this wave's bootstrap tier, has run).
#
# Exit codes:
#   0  ok (warnings allowed: unreachable endpoint, Service not deployed)
#   1  a port is unpublished, a Service has the wrong type/nodePort, or the
#      cluster is not running
#   2  bad flag
#
# Usage: ./show-endpoints.sh [--help]

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

case "${1:-}" in
    --help|-h) printf 'Usage: %s [--help]\nRead-only: shows URL, published, Service nodePort, reachable per endpoint.\n' "$(basename "$0")"; exit 0 ;;
    "") ;;
    *) printf 'unknown flag: %s\n' "$1" >&2; exit 2 ;;
esac

BOLD=""; GRN=""; RED=""; YEL=""; RST=""
if [[ -t 1 ]]; then
    BOLD=$'\033[1m'; GRN=$'\033[32m'; RED=$'\033[31m'; YEL=$'\033[33m'; RST=$'\033[0m'
fi

command -v python3 >/dev/null 2>&1 || { printf '%sERROR:%s python3 is required.\n' "$RED" "$RST"; exit 1; }
docker_engine_ok || exit 1

if ! { profile_container_exists && profile_container_running; }; then
    printf '%sERROR:%s cluster "%s" is not running.\n' "$RED" "$RST" "$EP_PROFILE"
    printf 'Start it with: ./setup-profile.sh\n'
    exit 1
fi

if ! kubectl --context "$EP_PROFILE" get --raw /readyz >/dev/null 2>&1; then
    printf '%sERROR:%s cannot query the API server for context "%s".\n' "$RED" "$RST" "$EP_PROFILE"
    exit 1
fi

pub="$(published_ports)"
fail=0; warn=0
errf="$(mktemp)"; trap 'rm -f "$errf"' EXIT

pad() { local plain="$1" w="$2"; printf '%*s' $(( w > ${#plain} ? w - ${#plain} : 0 )) ""; }

printf '%s%-12s %-50s %-10s %-30s %s%s\n' "$BOLD" NAME URL PUBLISHED "SVC NODEPORT" REACHABLE "$RST"
for name in "${ENDPOINT_NAMES[@]}"; do
    read -r hp np ns svc _ path <<<"$(_endpoint_row "$name")"

    if grep -qx "${np} ${hp}" <<<"$pub"; then p="${GRN}ok${RST}"; praw=ok; else p="${RED}MISSING${RST}"; praw=MISSING; fail=1; fi

    krc=0
    svc_out="$(kubectl --context "$EP_PROFILE" get svc -n "$ns" "$svc" \
            -o jsonpath='{.spec.type} {.spec.ports[*].nodePort}' 2>"${errf}")" || krc=$?
    kerr="$(cat "$errf" 2>/dev/null)"
    stype="${svc_out%% *}"
    if (( krc != 0 )); then
        if [[ "$kerr" == *"Error from server (NotFound)"* ]]; then
            sraw="not deployed"; s="${YEL}${sraw}${RST}"; warn=1
        else
            sraw="cannot query the API server"; s="${RED}${sraw}${RST}"; fail=1
        fi
    elif [[ "$stype" == "NodePort" || "$stype" == "LoadBalancer" ]] && grep -qw -- "$np" <<<"${svc_out#* }"; then
        s="${GRN}ok${RST}"; sraw="ok"
    elif [[ "$stype" != "NodePort" && "$stype" != "LoadBalancer" ]]; then
        sraw="type ${stype:-<none>}, expected NodePort ${np}"; s="${RED}${sraw}${RST}"; fail=1
    else
        sraw="expected ${np} got ${svc_out#* }"; s="${RED}${sraw}${RST}"; fail=1
    fi

    rc=0
    curl -s -o /dev/null --max-time 3 "http://127.0.0.1:${hp}/" >/dev/null 2>&1 || rc=$?
    if [[ "$rc" == 7 || "$rc" == 28 ]]; then r="${YEL}no${RST}"; warn=1; else r="${GRN}ok${RST}"; fi

    url="$(endpoint_url "$name")${path:-}"
    printf '%-12s %-50s %s%s %s%s %s\n' "$name" "$url" "$p" "$(pad "$praw" 10)" "$s" "$(pad "$sraw" 30)" "$r"
done

printf '\n'
if (( fail )); then
    printf '%sFAIL:%s ports are fixed at profile creation; recreate with: ./setup-profile.sh --replace\n' "$RED" "$RST"
    printf '(--replace deletes and recreates the cluster; run ./bootstrap.sh again afterwards.)\n'
    exit 1
fi
(( warn )) && printf '%sWARN:%s some endpoints are not answering yet or not deployed; re-run ./bootstrap.sh if a tier did not finish.\n' "$YEL" "$RST"
exit 0
