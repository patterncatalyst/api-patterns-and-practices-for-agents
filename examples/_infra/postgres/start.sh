#!/usr/bin/env bash
# Start command for the CloudNativePG operand image, which ships no entrypoint.
# Runs as UID 26. On an empty PGDATA it runs initdb with scram-sha-256 password
# auth, creates the application database, and applies postgres/init/*.sql once.
set -euo pipefail

: "${POSTGRES_USER:=apa}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
: "${POSTGRES_DB:=apa}"
: "${PGDATA:=/var/lib/postgresql/data}"
INIT_DIR="${INIT_DIR:-/opt/apa/init}"

if [[ ! -s "$PGDATA/PG_VERSION" ]]; then
    echo "start.sh: empty PGDATA, running initdb"
    pwfile="$(mktemp)"
    trap 'rm -f "$pwfile"' EXIT
    printf '%s' "$POSTGRES_PASSWORD" > "$pwfile"
    initdb --pgdata="$PGDATA" --username="$POSTGRES_USER" --pwfile="$pwfile" \
        --auth-local=scram-sha-256 --auth-host=scram-sha-256 \
        --encoding=UTF8 --locale=C.utf8
    echo "host all all all scram-sha-256" >> "$PGDATA/pg_hba.conf"

    # Temporary server on the local socket only, for database creation and init scripts.
    sockdir="$(mktemp -d)"
    pg_ctl --pgdata="$PGDATA" -w -o "-c listen_addresses='' -c unix_socket_directories=$sockdir" start
    export PGPASSWORD="$POSTGRES_PASSWORD"
    if [[ "$POSTGRES_DB" != "postgres" ]]; then
        createdb --host="$sockdir" --username="$POSTGRES_USER" "$POSTGRES_DB"
    fi
    shopt -s nullglob
    for f in "$INIT_DIR"/*.sql; do
        echo "start.sh: applying $(basename "$f")"
        psql --host="$sockdir" --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
            -v ON_ERROR_STOP=1 --file="$f"
    done
    unset PGPASSWORD
    pg_ctl --pgdata="$PGDATA" -m fast -w stop
    rm -f "$pwfile"; rm -rf "$sockdir"
fi

exec postgres \
    -c listen_addresses='*'
