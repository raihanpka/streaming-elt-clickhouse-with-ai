from pathlib import Path

import pytest

from replayer.mapping import load_mapping
from replayer.source import select_rows

FIXTURES = Path(__file__).parent / "fixtures"
MAPPING = load_mapping(FIXTURES / "mapping.yml")
CSV = FIXTURES / "sample.csv"


def numbers(**kwargs):
    return [r.number for r in select_rows(CSV, MAPPING, **kwargs)]


def test_reads_every_row_in_order_with_dataset_time():
    rows = list(select_rows(CSV, MAPPING))
    assert [r.number for r in rows] == list(range(1, 11))
    assert rows[3].time == 10.0
    assert rows[3].values["Class"] == "1"


def test_limit_stops_early():
    assert numbers(limit=3) == [1, 2, 3]


def test_since_and_until_use_dataset_time():
    assert numbers(since="10", until="40") == [4, 5, 6, 7, 8]


def test_since_after_all_rows_is_empty():
    assert numbers(since="1000") == []


def test_limit_applies_after_the_time_window():
    assert numbers(since="10", limit=2) == [4, 5]


def test_rows_out_of_order_are_rejected(tmp_path):
    bad = tmp_path / "bad.csv"
    bad.write_text("Time,Amount,Class,V1\n5,1,0,0\n1,1,0,0\n")
    with pytest.raises(ValueError, match="not sorted"):
        list(select_rows(bad, MAPPING))


def test_missing_time_column_is_a_clear_error(tmp_path):
    bad = tmp_path / "bad.csv"
    bad.write_text("Amount,Class\n1,0\n")
    with pytest.raises(ValueError, match="Time"):
        list(select_rows(bad, MAPPING))
