"""Reading dataset rows and selecting a slice of them."""

import csv
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from .mapping import DatasetMapping


@dataclass(frozen=True)
class Row:
    """One dataset row: its position, its time on the dataset scale, and its values."""

    number: int
    time: float
    values: dict[str, str]


def parse_time(value: str, unit: str) -> float:
    """Convert a time value of the dataset to seconds on the dataset scale."""
    if unit == "epoch_millis":
        return float(value) / 1000.0
    if unit == "iso8601":
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    return float(value)


def select_rows(
    path: Path,
    mapping: DatasetMapping,
    since: str | None = None,
    until: str | None = None,
    limit: int | None = None,
) -> Iterator[Row]:
    """Yield rows of a CSV file inside the time window, at most limit of them."""
    with Path(path).open(newline="") as handle:
        yield from rows_from_lines(handle, mapping, str(path), since, until, limit)


def rows_from_lines(
    lines: Iterable[str],
    mapping: DatasetMapping,
    origin: str,
    since: str | None = None,
    until: str | None = None,
    limit: int | None = None,
) -> Iterator[Row]:
    """Yield rows from CSV text lines, checking that time never goes back."""
    start = parse_time(since, mapping.time_unit) if since is not None else None
    end = parse_time(until, mapping.time_unit) if until is not None else None
    emitted = 0
    previous = float("-inf")
    reader = csv.DictReader(lines)
    if reader.fieldnames is None or mapping.time_column not in reader.fieldnames:
        raise ValueError(f"time column '{mapping.time_column}' not found in {origin}")
    for number, values in enumerate(reader, start=1):
        time = parse_time(values[mapping.time_column], mapping.time_unit)
        if time < previous:
            raise ValueError(f"{origin} is not sorted by '{mapping.time_column}' at row {number}")
        previous = time
        if start is not None and time < start:
            continue
        if end is not None and time > end:
            return
        if limit is not None and emitted >= limit:
            return
        emitted += 1
        yield Row(number, time, values)
