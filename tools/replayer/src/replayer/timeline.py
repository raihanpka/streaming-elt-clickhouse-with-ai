"""Rebasing dataset time to the wall clock and pacing the replay."""

import time as _time
from collections.abc import Callable


class Timeline:
    """Maps dataset time to wall clock time: the first event is at the start, spacing is divided by speed."""

    def __init__(
        self,
        first_time: float,
        start: float,
        speed: float,
        clock: Callable[[], float] = _time.time,
        sleep: Callable[[float], None] = _time.sleep,
    ) -> None:
        """Create a timeline. Speed 1 replays at the original pace, 100 is a hundred times faster."""
        if speed <= 0:
            raise ValueError("speed must be positive")
        self.first_time = first_time
        self.start = start
        self.speed = speed
        self._clock = clock
        self._sleep = sleep

    def event_time(self, dataset_time: float) -> float:
        """Return the wall clock time (epoch seconds) assigned to a dataset time."""
        return self.start + (dataset_time - self.first_time) / self.speed

    def wait_until(self, wall_time: float) -> None:
        """Sleep until the wall clock reaches the time, so events are never stamped in the future."""
        missing = wall_time - self._clock()
        if missing > 0:
            self._sleep(missing)
