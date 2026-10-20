from replayer.timeline import Timeline


def test_first_event_is_at_the_start_and_spacing_is_divided_by_speed():
    t = Timeline(first_time=100.0, start=1000.0, speed=10.0)
    assert t.event_time(100.0) == 1000.0
    assert t.event_time(200.0) == 1010.0


def test_wait_until_sleeps_only_for_the_missing_time():
    slept = []
    now = [1000.0]
    t = Timeline(first_time=0.0, start=1000.0, speed=1.0, clock=lambda: now[0], sleep=slept.append)
    t.wait_until(1005.0)
    assert slept == [5.0]
    now[0] = 1010.0
    t.wait_until(1005.0)
    assert slept == [5.0]


def test_speed_must_be_positive():
    import pytest

    with pytest.raises(ValueError):
        Timeline(first_time=0.0, start=0.0, speed=0.0)
