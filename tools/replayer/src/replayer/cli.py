"""Command line entry point: replay a dataset slice through the ingest API."""

import argparse
import json
import os
import sys
import time
from collections.abc import Callable
from datetime import UTC, datetime
from itertools import batched, chain
from pathlib import Path

from .events import build_event
from .mapping import DatasetMapping, load_mapping
from .sender import ReplayError, Sender
from .source import select_rows
from .timeline import Timeline


def iso(epoch_seconds: float) -> str:
    """Format epoch seconds as an RFC 3339 UTC timestamp with milliseconds."""
    return datetime.fromtimestamp(epoch_seconds, UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_args(argv: list[str]) -> argparse.Namespace:
    """Parse command line arguments. URL and key default to the BROMO_* variables."""
    p = argparse.ArgumentParser(prog="replay", description="Replay a dataset slice through the Bromo ingest API")
    p.add_argument("--mapping", required=True, type=Path, help="dataset mapping file, see datasets/")
    p.add_argument("--file", required=True, type=Path, help="CSV file of the dataset")
    p.add_argument("--limit", type=int, help="stop after this many rows")
    p.add_argument("--since", help="first dataset time to include, on the dataset time scale")
    p.add_argument("--until", help="last dataset time to include, on the dataset time scale")
    p.add_argument("--speed", type=float, default=1.0, help="1 is the original pace, 100 is a hundred times faster")
    p.add_argument("--batch-size", type=int, default=200)
    p.add_argument("--url", default=os.environ.get("BROMO_INGEST_URL", "http://localhost:8080/api/v1/events"))
    p.add_argument("--api-key", default=os.environ.get("BROMO_API_KEY", ""))
    p.add_argument("--summary", type=Path, default=Path("replay-summary.json"))
    p.add_argument("--dry-run", action="store_true", help="read and build events, send nothing")
    return p.parse_args(argv)


def run(argv: list[str], sleep: Callable[[float], None] = time.sleep) -> int:
    """Run a replay and return the process exit code. Rows are streamed, never loaded all at once."""
    args = parse_args(argv)
    mapping: DatasetMapping = load_mapping(args.mapping)
    rows = select_rows(args.file, mapping, args.since, args.until, args.limit)
    summary: dict[str, object] = {"dataset": mapping.name, "source": mapping.event_source, "planned": 0, "sent": 0}
    first = next(rows, None)
    if first is None:
        args.summary.write_text(json.dumps(summary))
        print("nothing to replay")
        return 0
    timeline = Timeline(first.time, time.time(), args.speed, sleep=sleep)
    sender = Sender(args.url, args.api_key, sleep=sleep)
    code = 0
    planned = 0
    sent = 0
    last = first
    try:
        for chunk in batched(chain([first], rows), args.batch_size):
            last = chunk[-1]
            planned += len(chunk)
            timeline.wait_until(timeline.event_time(last.time))
            events = [build_event(mapping, r, iso(timeline.event_time(r.time))) for r in chunk]
            if not args.dry_run:
                sent += sender.send(events)
    except ReplayError as error:
        print(f"replay failed: {error}", file=sys.stderr)
        code = 1
    summary.update(
        planned=planned,
        sent=sent,
        first_id=mapping.id_template.format(row=first.number, **first.values),
        last_id=mapping.id_template.format(row=last.number, **last.values),
    )
    args.summary.write_text(json.dumps(summary))
    print(f"planned={planned} sent={sent} source={mapping.event_source}")
    return code


def main() -> None:
    """Console script entry point."""
    sys.exit(run(sys.argv[1:]))
