from pathlib import Path

import pytest

from replayer.clickhouse_source import SourceError, fetch_text, render_query
from replayer.mapping import MappingError, load_mapping, parse_mapping

from .fake_clickhouse import FakeClickHouse

FIXTURES = Path(__file__).parent / "fixtures"
MAPPING = load_mapping(FIXTURES / "clickhouse-mapping.yml")


def test_mapping_reads_the_query_source_and_defaults():
    assert MAPPING.source_type == "clickhouse"
    assert "ORDER BY event_time" in MAPPING.query
    assert MAPPING.params["year"] == "2020"


def test_csv_is_the_default_source():
    assert load_mapping(FIXTURES / "mapping.yml").source_type == "csv"


def test_a_query_source_needs_a_query():
    raw = {
        "name": "d",
        "source": {"type": "clickhouse"},
        "time": {"column": "t", "unit": "seconds"},
        "event": {"type": "a.v1", "source": "s", "id": "x", "data": {"k": {"column": "k"}}},
    }
    with pytest.raises(MappingError, match="source.query"):
        parse_mapping(raw)


def test_unknown_placeholders_are_rejected_when_loading():
    raw = {
        "name": "d",
        "source": {"type": "clickhouse", "query": "SELECT {nope}", "params": {}},
        "time": {"column": "t", "unit": "seconds"},
        "event": {"type": "a.v1", "source": "s", "id": "x", "data": {"k": {"column": "k"}}},
    }
    with pytest.raises(MappingError, match="nope"):
        parse_mapping(raw)


def test_render_uses_defaults_and_overrides():
    sql = render_query(MAPPING, {"year": "2022", "limit": "50"})
    assert "y = 2022" in sql and "LIMIT 50" in sql and "toDate('2020-01-01')" in sql
    assert sql.rstrip().endswith("FORMAT CSVWithNames")


@pytest.mark.parametrize("value", ["2020'; DROP TABLE x; --", "1 OR 1=1", "a;b", "x\ny"])
def test_unsafe_parameter_values_are_refused(value):
    with pytest.raises(MappingError, match="year"):
        render_query(MAPPING, {"year": value})


def test_limit_must_be_a_positive_integer():
    with pytest.raises(MappingError, match="limit"):
        render_query(MAPPING, {"limit": "-5"})
    with pytest.raises(MappingError, match="limit"):
        render_query(MAPPING, {"limit": "many"})


def test_fetch_posts_the_query_with_credentials_and_streams_the_body():
    with FakeClickHouse("event_time,a,b\n1,2,x\n") as server:
        with fetch_text(server.url, "admin", "secret", "SELECT 1 FORMAT CSVWithNames") as text:
            assert text.read() == "event_time,a,b\n1,2,x\n"
    assert server.queries == ["SELECT 1 FORMAT CSVWithNames"]
    assert server.headers[0] == {"user": "admin", "key": "secret"}


def test_fetch_reports_a_server_error_with_its_message():
    with FakeClickHouse("Code: 62. syntax error", status=400) as server:
        with pytest.raises(SourceError, match="syntax error"):
            with fetch_text(server.url, "u", "p", "SELECT"):
                pass
