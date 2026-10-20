"""Building CloudEvents from dataset rows."""

from typing import Any

from .mapping import DatasetMapping, FieldMapping
from .source import Row

TRUE_VALUES = {"1", "true", "yes", "y", "t"}
NULL_VALUES = {"", "\\N"}


def cast_value(raw: str, spec: FieldMapping) -> Any:
    """Convert a CSV text value to the type named in the mapping, translating codes when asked."""
    if spec.values is not None:
        return spec.values.get(raw, raw)
    if spec.cast == "float":
        return float(raw)
    if spec.cast == "int":
        return int(float(raw))
    if spec.cast == "bool":
        return raw.strip().lower() in TRUE_VALUES
    return raw


def build_event(mapping: DatasetMapping, row: Row, event_time: str) -> dict[str, Any]:
    """Build one CloudEvent. Empty and null values are left out. The id depends only on the row."""
    context = {"row": row.number, **row.values}
    data: dict[str, Any] = {
        key: cast_value(row.values[spec.column], spec)
        for key, spec in mapping.data.items()
        if row.values[spec.column] not in NULL_VALUES
    }
    data["originalTime"] = row.values[mapping.time_column]
    event: dict[str, Any] = {
        "specversion": "1.0",
        "id": mapping.id_template.format_map(context),
        "source": mapping.event_source,
        "type": mapping.event_type,
        "time": event_time,
        "data": data,
    }
    if mapping.subject_template:
        subject = mapping.subject_template.format_map(context)
        if subject not in NULL_VALUES:
            event["subject"] = subject
    return event
