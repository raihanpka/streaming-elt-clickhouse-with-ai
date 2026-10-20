"""Reading rows from a ClickHouse query, so a slice can be sorted and filtered by the server."""

import io
import re
import urllib.error
import urllib.request
from collections.abc import Iterator
from contextlib import contextmanager

from .mapping import BUILTIN_PARAMS, DatasetMapping, MappingError

SAFE_VALUE = re.compile(r"^[A-Za-z0-9_.:+ \-]{0,64}$")


class SourceError(RuntimeError):
    """The ClickHouse source could not deliver the rows."""


def render_query(mapping: DatasetMapping, overrides: dict[str, str]) -> str:
    """Fill the placeholders of the mapping query. Values are checked, never trusted."""
    if mapping.query is None:
        raise MappingError("the mapping has no source.query")
    values = {**mapping.params, **{k: v for k, v in overrides.items() if v is not None}}
    for name in set(re.findall(r"\{(\w+)\}", mapping.query)):
        if name not in values:
            raise MappingError(f"no value for query placeholder '{name}', pass --param {name}=VALUE")
    for name, value in values.items():
        if not SAFE_VALUE.match(str(value)):
            raise MappingError(f"{name}: value contains characters that are not allowed")
        if name == "limit" and not (str(value).isdigit() and int(value) > 0):
            raise MappingError("limit: must be a positive integer")
    sql = mapping.query.format_map(values).strip().rstrip(";")
    return f"{sql} FORMAT CSVWithNames"


@contextmanager
def fetch_text(url: str, user: str, password: str, sql: str, timeout: float = 900.0) -> Iterator[io.TextIOWrapper]:
    """POST the query and yield the response as a text stream, raising SourceError on failure."""
    request = urllib.request.Request(
        url,
        data=sql.encode(),
        method="POST",
        headers={"X-ClickHouse-User": user, "X-ClickHouse-Key": password},
    )
    try:
        response = urllib.request.urlopen(request, timeout=timeout)  # noqa: S310
    except urllib.error.HTTPError as error:
        raise SourceError(f"ClickHouse answered {error.code}: {error.read().decode(errors='replace')[:400]}") from error
    except urllib.error.URLError as error:
        raise SourceError(f"cannot reach ClickHouse at {url}: {error.reason}") from error
    with response:
        yield io.TextIOWrapper(response, encoding="utf-8", newline="")


def builtin_overrides(since: str | None, until: str | None, limit: int | None) -> dict[str, str]:
    """Turn the command line window into query parameters, skipping the ones that were not given."""
    given = {"since": since, "until": until, "limit": None if limit is None else str(limit)}
    return {k: v for k, v in given.items() if k in BUILTIN_PARAMS and v is not None}
