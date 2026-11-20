#!/usr/bin/env bash
#
# teardown.sh - leave the machine clean: either delete just the namespaces
# this platform created (keeping the minikube profile itself, for a fast
# re-bootstrap) or delete the whole profile.
#
# Deleting the profile also frees the host ports it published on 127.0.0.1
# (lib/endpoints.sh has the full list), so a compose stack or another
# profile can bind them afterwards.
#
# Usage:
#   ./teardown.sh                  # delete the whole minikube profile
#   ./teardown.sh --namespaces-only  # delete only the namespaces, keep the profile
#
# Environment:
#   MINIKUBE_PROFILE   profile to delete (default: apa)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/endpoints.sh
source "${SCRIPT_DIR}/lib/endpoints.sh"

PROFILE_NAME="$EP_PROFILE"

MODE="profile"
case "${1:-}" in
    --namespaces-only) MODE="namespaces" ;;
    "") ;;
    *) printf 'unknown flag: %s\nUsage: %s [--namespaces-only]\n' "$1" "$(basename "$0")" >&2; exit 2 ;;
esac

if [[ "$MODE" == "namespaces" ]]; then
    printf '==> About to delete the namespaces this platform created from profile: %s\n' "$PROFILE_NAME"
    printf '    (apa, %s, %s, istio-system, cnpg-system). Continue? [y/N] ' "$EP_DATA_NS" "$EP_OBS_NS"
    read -r answer
    [[ "$answer" =~ ^[Yy] ]] || { printf 'Aborted.\n'; exit 1; }

    kubectl delete namespace apa "$EP_DATA_NS" "$EP_OBS_NS" istio-system cnpg-system --ignore-not-found
    printf '==> Namespaces deleted. Re-run ./bootstrap.sh to stand the platform back up.\n'
    exit 0
fi

printf '==> About to delete minikube profile: %s\n' "$PROFILE_NAME"
printf '    This will wipe the cluster, all images, all PVs. Continue? [y/N] '
read -r answer
[[ "$answer" =~ ^[Yy] ]] || { printf 'Aborted.\n'; exit 1; }

minikube delete -p "$PROFILE_NAME"
printf '==> Profile %s deleted.\n' "$PROFILE_NAME"
