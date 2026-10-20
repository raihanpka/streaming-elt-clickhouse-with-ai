"""Command line entry point: replay a dataset slice through the ingest API."""

import argparse
import json
import os
import sys
import time
from collections.abc import Callable
from datetime import UTC, datetime
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
    """Run a replay and return the process exit code."""
    args = parse_args(argv)
    mapping: DatasetMapping = load_mapping(args.mapping)
    rows = list(select_rows(args.file, mapping, args.since, args.until, args.limit))
    summary = {"dataset": mapping.name, "source": mapping.event_source, "planned": len(rows), "sent": 0}
    if not rows:
        args.summary.write_text(json.dumps(summary))
        print("nothing to replay")
        return 0
    timeline = Timeline(rows[0].time, time.time(), args.speed, sleep=sleep)
    sender = Sender(args.url, args.api_key, sleep=sleep)
    code = 0
    try:
        for start in range(0, len(rows), args.batch_size):
            chunk = rows[start : start + args.batch_size]
            timeline.wait_until(timeline.event_time(chunk[-1].time))
            events = [build_event(mapping, r, iso(timeline.event_time(r.time))) for r in chunk]
            if not args.dry_run:
                summary["sent"] += sender.send(events)
    except ReplayError as error:
        print(f"replay failed: {error}", file=sys.stderr)
        code = 1
    summary["first_id"] = mapping.id_template.format(row=rows[0].number, **rows[0].values)
    summary["last_id"] = mapping.id_template.format(row=rows[-1].number, **rows[-1].values)
    args.summary.write_text(json.dumps(summary))
    print(f"planned={summary['planned']} sent={summary['sent']} source={mapping.event_source}")
    return code


def main() -> None:
    """Console script entry point."""
    sys.exit(run(sys.argv[1:]))
