#!/usr/bin/env bash
# Maintenance commands against the compose ClickHouse, run as the admin user.
#   maintenance/run.sh reconcile        compare each layer with the one before it
#   maintenance/run.sh reprocess        rebuild silver and gold from bronze (bronze is not touched)
#   maintenance/run.sh count SOURCE     distinct event ids in silver for one source, to compare with a replay
# Needs CLICKHOUSE_ADMIN_PASSWORD in the environment (for example: set -a; . ./.env; set +a).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CONTAINER="${CLICKHOUSE_CONTAINER:-bromo-clickhouse}"
: "${CLICKHOUSE_ADMIN_PASSWORD:?set CLICKHOUSE_ADMIN_PASSWORD}"

# shellcheck source=../tests/contract-examples.sh
. "$HERE/../tests/contract-examples.sh"

# Runs clickhouse-client in the container as admin, reading SQL from stdin.
admin() { docker exec -i "$CONTAINER" clickhouse-client --user admin --password "$CLICKHOUSE_ADMIN_PASSWORD" "$@"; }

case "${1:-}" in
  reconcile) admin --format PrettyCompactMonoBlock < "$HERE/reconcile.sql" ;;
  reprocess) admin --multiquery < "$HERE/reprocess.sql" && echo "silver and gold rebuilt from bronze" ;;
  count) admin --query "SELECT uniqExact(event_id) FROM $SILVER_TABLE WHERE source = '${2:?usage: run.sh count SOURCE}'" ;;
  *) echo "usage: run.sh reconcile | reprocess | count SOURCE" >&2; exit 2 ;;
esac
