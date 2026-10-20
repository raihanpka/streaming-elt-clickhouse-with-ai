from pathlib import Path

import pytest

from replayer.mapping import MappingError, load_mapping, parse_mapping

FIXTURES = Path(__file__).parent / "fixtures"


def test_loads_a_valid_mapping():
    m = load_mapping(FIXTURES / "mapping.yml")
    assert m.name == "test-dataset"
    assert m.time_column == "Time"
    assert m.time_unit == "seconds"
    assert m.event_type == "com.example.payment.v1"
    assert m.data["amount"].cast == "float"
    assert m.data["label"].values == {"0": "ok", "1": "fraud"}


def raw(**overrides):
    base = {
        "name": "d",
        "time": {"column": "Time", "unit": "seconds"},
        "event": {"type": "a.v1", "source": "s", "id": "x-{row}", "data": {"k": {"column": "K"}}},
    }
    for key, value in overrides.items():
        base[key] = value
    return base


def test_reports_every_problem_together():
    bad = raw(
        time={"column": "", "unit": "weeks"},
        event={"type": "", "source": "s", "id": "", "data": {"k": {"column": "K", "cast": "decimal"}}},
    )
    with pytest.raises(MappingError) as err:
        parse_mapping(bad)
    text = str(err.value)
    assert (
        "time.column" in text and "time.unit" in text and "event.type" in text and "event.id" in text and "cast" in text
    )


def test_data_is_required():
    with pytest.raises(MappingError):
        parse_mapping(raw(event={"type": "a.v1", "source": "s", "id": "x"}))
