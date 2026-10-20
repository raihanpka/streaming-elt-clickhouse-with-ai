# Bronze, silver, and gold layers. Everything specific to the data comes from contract-examples.sh,
# which is generated from config/data-contract.yml, so this file works for any contract.

TS="$(q "SELECT formatDateTime(now() - 60, '%Y-%m-%dT%H:%i:%SZ', 'UTC')")"

insert_raw() { as ingest_writer "$INGEST_PW" "INSERT INTO $DB.raw (request_id, payload) VALUES (generateUUIDv4(), '$1')"; }

sample_event() {
  echo '{"specversion":"1.0","id":"'"$1"'","source":"'"$SAMPLE_SOURCE"'","type":"'"$SAMPLE_TYPE"'","time":"'"$TS"'","subject":"'"$SAMPLE_SUBJECT"'","data":'"$SAMPLE_DATA"'}'
}

insert_raw "$(sample_event sample-1)"
assert_eq "1" "$(q "SELECT count() FROM $DB.events WHERE event_id = 'sample-1'")" "valid event reaches events"
assert_eq "$TS" "$(q "SELECT formatDateTime(time, '%Y-%m-%dT%H:%i:%SZ', 'UTC') FROM $DB.events WHERE event_id = 'sample-1'")" "event time is parsed in UTC"
for check in "${SAMPLE_FIELD_CHECKS[@]}"; do
  col="${check%%|*}"; expected="${check#*|}"
  assert_eq "$expected" "$(q "SELECT \`$col\` FROM $DB.events WHERE event_id = 'sample-1'")" "promoted field $col is typed and parsed"
done
for check in "${SAMPLE_ATTR_CHECKS[@]}"; do
  key="${check%%|*}"; expected="${check#*|}"
  assert_eq "$expected" "$(q "SELECT attrs['$key'] FROM $DB.events WHERE event_id = 'sample-1'")" "other data field $key goes to attrs"
done

insert_raw 'not json'
insert_raw '{"specversion":"1.0","source":"s1","type":"t.v1","time":"'"$TS"'","marker":"no-id"}'
insert_raw '{"specversion":"1.0","id":"bad-time-1","source":"s1","type":"t.v1","time":"yesterday"}'
assert_eq "invalid_json" "$(q "SELECT reason FROM $DB.events_rejected WHERE payload = 'not json'")" "malformed json is rejected with a reason"
assert_eq "missing_id" "$(q "SELECT reason FROM $DB.events_rejected WHERE payload LIKE '%no-id%'")" "missing id is rejected"
assert_eq "bad_time" "$(q "SELECT reason FROM $DB.events_rejected WHERE payload LIKE '%bad-time-1%'")" "bad time is rejected"
assert_eq "0" "$(q "SELECT count() FROM $DB.events WHERE event_id = 'bad-time-1'")" "rejected events are not in events"

insert_raw "$(sample_event dup-1)"
insert_raw "$(sample_event dup-1)"
VALID_ROWS="$(q "SELECT count() FROM $DB.events")"
assert_eq "$VALID_ROWS" "$(q "SELECT sum(events) FROM $DB.mart_events_1m")" "mart counts every inserted event"
q "OPTIMIZE TABLE $DB.events FINAL" >/dev/null
assert_eq "1" "$(q "SELECT count() FROM $DB.events WHERE event_id = 'dup-1'")" "same event id collapses after merge"
assert_eq "2" "$(q "SELECT count() FROM $DB.raw WHERE payload LIKE '%dup-1%'")" "raw keeps both rows"
assert_eq "$VALID_ROWS" "$(q "SELECT sum(events) FROM $DB.mart_events_1m")" "mart keeps the duplicate until rebuilt (documented ceiling)"

assert_eq "$VALID_ROWS" "$(as api_reader "$API_PW" "SELECT sum(events) FROM $DB.mart_events_1m")" "api_reader can read the mart"
assert_fails "api_reader cannot read events" as api_reader "$API_PW" "SELECT count() FROM $DB.events"
assert_fails "ingest_writer cannot select from raw" as ingest_writer "$INGEST_PW" "SELECT count() FROM $DB.raw"
assert_fails "ingest_writer cannot insert into events" as ingest_writer "$INGEST_PW" "INSERT INTO $DB.events (event_id) VALUES ('x')"
assert_fails "api_reader cannot insert into raw" as api_reader "$API_PW" "INSERT INTO $DB.raw (request_id, payload) VALUES (generateUUIDv4(), '{}')"
assert_fails "api_reader cannot select from raw" as api_reader "$API_PW" "SELECT count() FROM $DB.raw"
assert_eq "0" "$(q "SELECT count() FROM system.columns AS c INNER JOIN system.tables AS t ON c.database = t.database AND c.table = t.name WHERE c.database = '$DB' AND c.comment = '' AND t.engine != 'MaterializedView'")" "every column has a comment"
assert_eq "0" "$(q "SELECT count() FROM system.tables WHERE database = '$DB' AND comment = ''")" "every table has a comment"
