"""Dataset mapping files: how the columns of a dataset become CloudEvents."""

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

CASTS = {"string", "float", "int", "bool"}
SOURCE_TYPES = {"csv", "clickhouse"}
BUILTIN_PARAMS = {"since", "until", "limit"}
TIME_UNITS = {"seconds", "epoch_seconds", "epoch_millis", "iso8601"}


class MappingError(ValueError):
    """The mapping file is invalid. The message lists every problem."""


@dataclass(frozen=True)
class FieldMapping:
    """One key of the event data, read from one column."""

    column: str
    cast: str = "string"
    values: dict[str, str] | None = None


@dataclass(frozen=True)
class DatasetMapping:
    """Everything the replayer needs to know about a dataset."""

    name: str
    license_url: str
    time_column: str
    time_unit: str
    event_type: str
    event_source: str
    id_template: str
    subject_template: str | None
    data: dict[str, FieldMapping] = field(default_factory=dict)
    source_type: str = "csv"
    query: str | None = None
    params: dict[str, str] = field(default_factory=dict)


def load_mapping(path: Path) -> DatasetMapping:
    """Read and validate a mapping file."""
    return parse_mapping(yaml.safe_load(Path(path).read_text()))


def parse_mapping(raw: dict[str, Any]) -> DatasetMapping:
    """Validate a parsed mapping and report all problems together."""
    problems: list[str] = []

    def text(section: dict[str, Any], key: str, where: str, required: bool = True) -> str:
        value = section.get(key)
        if value is None or str(value).strip() == "":
            if required:
                problems.append(f"{where}: required")
            return ""
        return str(value)

    time = raw.get("time") or {}
    event = raw.get("event") or {}
    time_column = text(time, "column", "time.column")
    time_unit = text(time, "unit", "time.unit")
    if time_unit and time_unit not in TIME_UNITS:
        problems.append(f"time.unit: use one of {sorted(TIME_UNITS)}")
    event_type = text(event, "type", "event.type")
    event_source = text(event, "source", "event.source")
    id_template = text(event, "id", "event.id")
    subject = text(event, "subject", "event.subject", required=False) or None
    data: dict[str, FieldMapping] = {}
    for key, spec in (event.get("data") or {}).items():
        spec = spec or {}
        column = text(spec, "column", f"event.data.{key}.column")
        cast = str(spec.get("cast", "string"))
        if cast not in CASTS:
            problems.append(f"event.data.{key}.cast: use one of {sorted(CASTS)}")
        values = {str(k): str(v) for k, v in spec["values"].items()} if spec.get("values") else None
        data[key] = FieldMapping(column, cast, values)
    if not data:
        problems.append("event.data: map at least one key")
    source = raw.get("source") or {}
    source_type = str(source.get("type", "csv"))
    query = source.get("query")
    params = {str(k): str(v) for k, v in (source.get("params") or {}).items()}
    if source_type not in SOURCE_TYPES:
        problems.append(f"source.type: use one of {sorted(SOURCE_TYPES)}")
    if source_type == "clickhouse":
        if not query or not str(query).strip():
            problems.append("source.query: required for a clickhouse source")
        else:
            query = " ".join(str(query).split())
            unknown = set(re.findall(r"\{(\w+)\}", query)) - set(params) - BUILTIN_PARAMS
            if unknown:
                problems.append(f"source.query: placeholders {sorted(unknown)} are not listed under source.params")
            if re.search(r"\bFORMAT\b", query, re.IGNORECASE):
                problems.append("source.query: leave FORMAT out, the replayer adds CSVWithNames")
    if problems:
        raise MappingError("Invalid dataset mapping:\n  * " + "\n  * ".join(problems))
    return DatasetMapping(
        name=str(raw.get("name", "dataset")),
        license_url=str(raw.get("license", "")),
        time_column=time_column,
        time_unit=time_unit,
        event_type=event_type,
        event_source=event_source,
        id_template=id_template,
        subject_template=subject,
        data=data,
        source_type=source_type,
        query=str(query) if query else None,
        params=params,
    )
