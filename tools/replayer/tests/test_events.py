from pathlib import Path

from replayer.events import build_event
from replayer.mapping import load_mapping
from replayer.source import select_rows

FIXTURES = Path(__file__).parent / "fixtures"
MAPPING = load_mapping(FIXTURES / "mapping.yml")


def first_row():
    return next(select_rows(FIXTURES / "sample.csv", MAPPING))


def test_builds_a_cloudevent_from_the_mapping():
    event = build_event(MAPPING, first_row(), "2026-10-20T10:00:00.000Z")
    assert event["specversion"] == "1.0"
    assert event["id"] == "test-1"
    assert event["source"] == "test-replay"
    assert event["type"] == "com.example.payment.v1"
    assert event["time"] == "2026-10-20T10:00:00.000Z"
    assert event["subject"] == "card-1.5"
    assert event["data"]["amount"] == 149.62
    assert event["data"]["label"] == "ok"


def test_keeps_the_original_time_in_data():
    event = build_event(MAPPING, first_row(), "2026-10-20T10:00:00.000Z")
    assert event["data"]["originalTime"] == "0"


def test_value_map_translates_codes():
    rows = list(select_rows(FIXTURES / "sample.csv", MAPPING))
    assert build_event(MAPPING, rows[3], "2026-10-20T10:00:00.000Z")["data"]["label"] == "fraud"


def test_event_ids_are_stable_across_runs():
    a = build_event(MAPPING, first_row(), "2026-10-20T10:00:00.000Z")["id"]
    b = build_event(MAPPING, first_row(), "2026-10-20T11:00:00.000Z")["id"]
    assert a == b


def row_with(**overrides):
    from replayer.source import Row

    values = {"Time": "5", "V1": "", "Amount": "10.5", "Class": "0"}
    values.update(overrides)
    return Row(7, 5.0, values)


def test_empty_and_null_values_are_left_out_of_data():
    event = build_event(MAPPING, row_with(Amount="", Class="\\N"), "2026-10-20T10:00:00.000Z")
    assert "amount" not in event["data"]
    assert "label" not in event["data"]
    assert event["data"]["originalTime"] == "5"


def test_empty_subject_is_left_out():
    from dataclasses import replace

    mapping = replace(MAPPING, subject_template="{V1}")
    assert "subject" not in build_event(mapping, row_with(V1=""), "2026-10-20T10:00:00.000Z")
    assert build_event(mapping, row_with(V1="tail-1"), "2026-10-20T10:00:00.000Z")["subject"] == "tail-1"
