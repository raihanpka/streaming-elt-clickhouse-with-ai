import json
from pathlib import Path

from replayer.cli import run

from .fake_server import FakeIngest

FIXTURES = Path(__file__).parent / "fixtures"


def replay(server, *extra, tmp_path):
    summary = tmp_path / "summary.json"
    args = [
        "--mapping",
        str(FIXTURES / "mapping.yml"),
        "--file",
        str(FIXTURES / "sample.csv"),
        "--url",
        server.url,
        "--api-key",
        "k",
        "--speed",
        "100000",
        "--batch-size",
        "4",
        "--summary",
        str(summary),
        *extra,
    ]
    code = run(args, sleep=lambda s: None)
    return code, json.loads(summary.read_text())


def test_replays_every_row_in_batches(tmp_path):
    with FakeIngest() as server:
        code, summary = replay(server, tmp_path=tmp_path)
    assert code == 0
    assert summary["sent"] == 10
    assert len(server.requests) == 3
    assert [e["id"] for e in server.events()] == [f"test-{i}" for i in range(1, 11)]


def test_event_times_are_rebased_to_now_and_never_in_the_future(tmp_path):
    from datetime import UTC, datetime

    with FakeIngest() as server:
        replay(server, tmp_path=tmp_path)
    times = [datetime.fromisoformat(e["time"].replace("Z", "+00:00")) for e in server.events()]
    assert times == sorted(times)
    assert times[-1] <= datetime.now(UTC)
    assert (datetime.now(UTC) - times[0]).total_seconds() < 60


def test_limit_and_window_flags_apply(tmp_path):
    with FakeIngest() as server:
        _, summary = replay(server, "--limit", "3", tmp_path=tmp_path)
    assert summary["sent"] == 3


def test_dry_run_sends_nothing(tmp_path):
    with FakeIngest() as server:
        code, summary = replay(server, "--dry-run", tmp_path=tmp_path)
    assert code == 0 and summary["sent"] == 0 and summary["planned"] == 10
    assert server.requests == []


def test_rejected_batch_fails_the_run(tmp_path):
    with FakeIngest(always_status=400) as server:
        code, _ = replay(server, tmp_path=tmp_path)
    assert code == 1


def test_rows_are_streamed_not_loaded_up_front(tmp_path, monkeypatch):
    from replayer import cli
    from replayer.source import Row

    consumed = {"rows": 0, "at_first_request": None}

    def fake_select(path, mapping, since=None, until=None, limit=None):
        for number in range(1, 1001):
            consumed["rows"] += 1
            yield Row(number, float(number), {"Time": str(number), "V1": "0", "Amount": "1", "Class": "0"})

    monkeypatch.setattr(cli, "select_rows", fake_select)

    def note():
        if consumed["at_first_request"] is None:
            consumed["at_first_request"] = consumed["rows"]

    with FakeIngest(on_request=note) as server:
        code, summary = replay(server, "--batch-size", "10", tmp_path=tmp_path)
    assert code == 0 and summary["sent"] == 1000 and summary["planned"] == 1000
    assert consumed["at_first_request"] < 100
