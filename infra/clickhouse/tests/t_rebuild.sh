# Rebuilding silver and gold from bronze, and the reconciliation checks between layers.

MAINT="$INIT_DIR/maintenance"

# Every reconciliation row must be healthy.
reconcile_failures() { q "SELECT count() FROM ($(cat "$MAINT/reconcile.sql")) WHERE consistent = 0"; }

assert_eq "0" "$(reconcile_failures)" "all layers reconcile before a rebuild"

bronze_rows="$(q "SELECT count() FROM $BRONZE_TABLE")"
silver_keys="$(q "SELECT uniqExact(source, event_id) FROM $SILVER_TABLE")"
rejected_rows="$(q "SELECT count() FROM $SILVER_REJECTED_TABLE")"
gold_before=()
for table in "${GOLD_TABLES[@]}"; do gold_before+=("$(q "SELECT sum(events) FROM $table")"); done

qfile "$MAINT/reprocess.sql" >/dev/null

assert_eq "$silver_keys" "$(q "SELECT uniqExact(source, event_id) FROM $SILVER_TABLE")" "a rebuild keeps every distinct silver event"
assert_eq "$rejected_rows" "$(q "SELECT count() FROM $SILVER_REJECTED_TABLE")" "a rebuild keeps every rejected row"
i=0
for table in "${GOLD_TABLES[@]}"; do
  assert_eq "${gold_before[$i]}" "$(q "SELECT sum(events) FROM $table")" "a rebuild keeps the totals of $table"
  i=$((i + 1))
done
assert_eq "0" "$(reconcile_failures)" "all layers reconcile after a rebuild"
assert_eq "$bronze_rows" "$(q "SELECT count() FROM $BRONZE_TABLE")" "bronze is untouched by a rebuild"
