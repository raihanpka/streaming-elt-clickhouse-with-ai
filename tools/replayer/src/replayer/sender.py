"""Sending batches to the ingest API with retries."""

import json
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Any

RETRYABLE = {429, 502, 503, 504}


class ReplayError(RuntimeError):
    """A batch could not be delivered."""


class Sender:
    """Posts CloudEvents batches. Retries are safe because event ids are stable and the API deduplicates."""

    def __init__(
        self,
        url: str,
        api_key: str,
        max_attempts: int = 8,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        """Create a sender for the ingest URL and bearer key."""
        self.url = url
        self.api_key = api_key
        self.max_attempts = max_attempts
        self._sleep = sleep

    def send(self, events: list[dict[str, Any]]) -> int:
        """Send one batch and return how many events the API accepted."""
        body = json.dumps(events).encode()
        delay = 0.5
        last = "no attempt made"
        for attempt in range(1, self.max_attempts + 1):
            request = urllib.request.Request(
                self.url,
                data=body,
                method="POST",
                headers={
                    "Content-Type": "application/cloudevents-batch+json",
                    "Authorization": f"Bearer {self.api_key}",
                },
            )
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    return int(json.loads(response.read())["accepted"])
            except urllib.error.HTTPError as error:
                detail = error.read().decode(errors="replace")[:300]
                if error.code not in RETRYABLE:
                    raise ReplayError(f"ingest rejected the batch with {error.code}: {detail}") from error
                last = f"{error.code} {detail}"
                retry_after = error.headers.get("Retry-After")
                wait = float(retry_after) if retry_after and retry_after.isdigit() else delay
            except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
                last = str(error)
                wait = delay
            if attempt < self.max_attempts:
                self._sleep(wait)
                delay = min(delay * 2, 30.0)
        raise ReplayError(f"gave up after {self.max_attempts} attempts: {last}")
