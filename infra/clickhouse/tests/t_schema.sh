# Bronze and silver layers: parsing, rejects, deduplication, grants.

TS="$(q "SELECT formatDateTime(now() - 60, '%Y-%m-%dT%H:%i:%SZ', 'UTC')")"
insert_raw() { as ingest_writer "$INGEST_PW" "INSERT INTO bromo.raw (request_id, payload) VALUES (generateUUIDv4(), '$1')"; }

insert_raw '{"specversion":"1.0","id":"e1","source":"s1","type":"com.example.payment.v1","time":"'"$TS"'","subject":"card-1","data":{"amount":42.5,"label":"ok","merchant":"m1"}}'
assert_eq "1" "$(q "SELECT count() FROM bromo.events WHERE event_id = 'e1'")" "valid event reaches events"
assert_eq "42.5" "$(q "SELECT amount FROM bromo.events WHERE event_id = 'e1'")" "amount is parsed"
assert_eq "ok" "$(q "SELECT label FROM bromo.events WHERE event_id = 'e1'")" "label is parsed"
assert_eq "m1" "$(q "SELECT attrs['merchant'] FROM bromo.events WHERE event_id = 'e1'")" "other data fields go to attrs"
assert_eq "$TS" "$(q "SELECT formatDateTime(time, '%Y-%m-%dT%H:%i:%SZ', 'UTC') FROM bromo.events WHERE event_id = 'e1'")" "event time is parsed in UTC"

insert_raw 'not json'
insert_raw '{"specversion":"1.0","source":"s1","type":"t.v1","time":"'"$TS"'"}'
insert_raw '{"specversion":"1.0","id":"e3","source":"s1","type":"t.v1","time":"yesterday"}'
assert_eq "invalid_json" "$(q "SELECT reason FROM bromo.events_rejected WHERE payload = 'not json'")" "malformed json is rejected with a reason"
assert_eq "missing_id" "$(q "SELECT reason FROM bromo.events_rejected WHERE payload LIKE '%\"type\":\"t.v1\"%' AND payload NOT LIKE '%\"id\"%'")" "missing id is rejected"
assert_eq "bad_time" "$(q "SELECT reason FROM bromo.events_rejected WHERE payload LIKE '%\"id\":\"e3\"%'")" "bad time is rejected"
assert_eq "0" "$(q "SELECT count() FROM bromo.events WHERE event_id = 'e3'")" "rejected events are not in events"

insert_raw '{"specversion":"1.0","id":"dup1","source":"s1","type":"t.v1","time":"'"$TS"'","data":{"amount":1}}'
insert_raw '{"specversion":"1.0","id":"dup1","source":"s1","type":"t.v1","time":"'"$TS"'","data":{"amount":1}}'
q "OPTIMIZE TABLE bromo.events FINAL" >/dev/null
assert_eq "1" "$(q "SELECT count() FROM bromo.events WHERE event_id = 'dup1'")" "same event id collapses after merge"
assert_eq "2" "$(q "SELECT count() FROM bromo.raw WHERE payload LIKE '%dup1%'")" "raw keeps both rows"

assert_fails "ingest_writer cannot select from raw" as ingest_writer "$INGEST_PW" "SELECT count() FROM bromo.raw"
assert_fails "ingest_writer cannot insert into events" as ingest_writer "$INGEST_PW" "INSERT INTO bromo.events (event_id) VALUES ('x')"
assert_fails "api_reader cannot insert into raw" as api_reader "$API_PW" "INSERT INTO bromo.raw (request_id, payload) VALUES (generateUUIDv4(), '{}')"
assert_fails "api_reader cannot select from raw" as api_reader "$API_PW" "SELECT count() FROM bromo.raw"
assert_eq "0" "$(q "SELECT count() FROM system.columns AS c INNER JOIN system.tables AS t ON c.database = t.database AND c.table = t.name WHERE c.database = 'bromo' AND c.comment = '' AND t.engine != 'MaterializedView'")" "every column has a comment"
assert_eq "0" "$(q "SELECT count() FROM system.tables WHERE database = 'bromo' AND comment = ''")" "every table has a comment"
