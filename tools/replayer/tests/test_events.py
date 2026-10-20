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
