#!/usr/bin/env bash
# Starts a throwaway ClickHouse with the init scripts and runs every tests/t_*.sh against it.
# Usage: tests/run.sh                         test the committed scripts
#        CONTRACT_FILE=my.yml tests/run.sh    generate from another contract first and test that
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
INIT_DIR="$(dirname "$HERE")"
if [ -n "${CONTRACT_FILE:-}" ]; then
  INIT_DIR="$(mktemp -d)"
  (cd "$HERE/../../.." && ./gradlew -q :bromo-app:generateWarehouseSql -PcontractFile="$CONTRACT_FILE" -PoutputDir="$INIT_DIR")
fi
IMAGE="$(grep -E '^\s+image: clickhouse' "$HERE/../../docker/docker-compose.yml" | awk '{print $2}')"
export NAME="bromo-sqltest-$$"
export ADMIN_PW="admin-test-pw" INGEST_PW="ingest-test-pw" API_PW="api-test-pw" AGENT_PW="agent-test-pw" FINDINGS_PW="findings-test-pw"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" \
  -e CLICKHOUSE_USER=admin -e CLICKHOUSE_PASSWORD="$ADMIN_PW" -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 \
  -e CLICKHOUSE_INGEST_PASSWORD="$INGEST_PW" -e CLICKHOUSE_API_PASSWORD="$API_PW" \
  -e CLICKHOUSE_AGENT_PASSWORD="$AGENT_PW" -e CLICKHOUSE_FINDINGS_PASSWORD="$FINDINGS_PW" \
  -v "$INIT_DIR:/docker-entrypoint-initdb.d:ro" "$IMAGE" >/dev/null

for _ in $(seq 1 90); do
  if docker logs "$NAME" 2>&1 | grep -q "init process done" \
     && docker exec "$NAME" wget -q --spider http://localhost:8123/ping 2>/dev/null; then
    break
  fi
  sleep 1
done
if docker logs "$NAME" 2>&1 | grep -qE "Exception|Code: [0-9]+" && ! docker exec "$NAME" wget -q --spider http://localhost:8123/ping 2>/dev/null; then
  docker logs "$NAME" 2>&1 | tail -20
  echo "FAIL: ClickHouse did not start" >&2
  exit 1
fi

# shellcheck source=lib.sh
. "$HERE/lib.sh"
# shellcheck source=contract-examples.sh
. "$INIT_DIR/tests/contract-examples.sh"
for f in "$HERE"/t_*.sh; do
  echo "== $(basename "$f")"
  # shellcheck disable=SC1090
  . "$f"
done
summary
