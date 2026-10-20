import pytest

from replayer.sender import ReplayError, Sender

from .fake_server import FakeIngest

EVENTS = [{"id": "a"}, {"id": "b"}]


def sender(url, **kwargs):
    return Sender(url, "key-1", sleep=lambda s: None, **kwargs)


def test_posts_a_batch_with_bearer_key_and_batch_content_type():
    with FakeIngest() as server:
        assert sender(server.url).send(EVENTS) == 2
    request = server.requests[0]
    assert request["auth"] == "Bearer key-1"
    assert request["type"] == "application/cloudevents-batch+json"


def test_retries_on_503_and_succeeds():
    with FakeIngest(fail_first=2) as server:
        assert sender(server.url).send(EVENTS) == 2
        assert len(server.requests) == 3


def test_gives_up_after_the_maximum_attempts():
    with FakeIngest(always_status=503) as server:
        with pytest.raises(ReplayError):
            sender(server.url, max_attempts=3).send(EVENTS)
        assert len(server.requests) == 3


def test_does_not_retry_a_400():
    with FakeIngest(always_status=400) as server:
        with pytest.raises(ReplayError) as err:
            sender(server.url).send(EVENTS)
        assert len(server.requests) == 1
        assert "400" in str(err.value)


def test_retries_when_the_server_is_unreachable():
    s = sender("http://127.0.0.1:9/api/v1/events", max_attempts=2)
    with pytest.raises(ReplayError):
        s.send(EVENTS)
