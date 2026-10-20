# Test helpers. Expects NAME and the *_PW variables from run.sh.
PASS=0
FAIL=0

# Runs a query as the admin user.
q() { docker exec "$NAME" clickhouse-client --user admin --password "$ADMIN_PW" --query "$1"; }

# Runs a query as another user: as USER PASSWORD SQL.
as() { docker exec "$NAME" clickhouse-client --user "$1" --password "$2" --query "$3"; }

# Compares two values: assert_eq EXPECTED ACTUAL MESSAGE.
assert_eq() {
  if [ "$1" = "$2" ]; then PASS=$((PASS + 1)); echo "  ok   $3"
  else FAIL=$((FAIL + 1)); echo "  FAIL $3 (expected '$1', got '$2')"; fi
}

# Expects a command to fail: assert_fails MESSAGE COMMAND...
assert_fails() {
  local msg="$1"; shift
  if "$@" >/dev/null 2>&1; then FAIL=$((FAIL + 1)); echo "  FAIL $msg (command succeeded)"
  else PASS=$((PASS + 1)); echo "  ok   $msg"; fi
}

# Prints totals and exits non zero when a check failed.
summary() {
  echo "passed=$PASS failed=$FAIL"
  [ "$FAIL" -eq 0 ]
}
