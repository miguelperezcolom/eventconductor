import pytest

from eventconductor_worker import ApiError, EngineClient, TaskGone


def test_activate_complete_and_the_engine_records_it(engine):
    engine.add("t1", "payments", {"amount": "42"}, attempt=2)
    client = EngineClient(engine.url, api_key="key")

    tasks = client.activate("w1", ["payments"], max_tasks=5, lease_ms=30_000, wait_ms=0)

    assert [t["id"] for t in tasks] == ["t1"]
    assert tasks[0]["variables"] == {"amount": "42"}
    assert tasks[0]["attempt"] == 2
    client.complete("t1", 2, {"chargeId": "ch-1", "total": 1.5})
    assert engine.completed == [{"id": "t1", "attempt": 2, "variables": {"chargeId": "ch-1", "total": 1.5}}]


def test_an_empty_long_poll_returns_no_tasks(engine):
    assert EngineClient(engine.url, "key").activate("w1", ["payments"], wait_ms=100) == []


def test_fail_heartbeat_and_inject(engine):
    engine.add("t1", "payments")
    client = EngineClient(engine.url + "/", "key")
    task = client.activate("w1", ["payments"], wait_ms=0)[0]

    assert client.heartbeat("t1", task["leaseToken"], 10_000) == {"cancelled": False,
                                                                  "deadline": "2026-01-01T00:00:00Z"}
    client.inject("t1", 1, [{"id": "extra", "type": "ACTION"}])
    client.fail("t1", 1, "CARD_DECLINED", "no funds", retryable=False)

    assert engine.injected[0]["steps"] == [{"id": "extra", "type": "ACTION"}]
    assert engine.failed == [{"id": "t1", "attempt": 1, "code": "CARD_DECLINED", "message": "no funds",
                              "retryable": False}]


def test_a_stale_or_gone_task_raises_task_gone_with_the_reason(engine):
    engine.add("t1", "payments", attempt=2)
    client = EngineClient(engine.url, "key")
    client.activate("w1", ["payments"], wait_ms=0)

    with pytest.raises(TaskGone) as stale:
        client.complete("t1", 1)
    assert stale.value.status == 409 and stale.value.reason == "STALE_ATTEMPT"

    with pytest.raises(TaskGone) as gone:
        client.complete("nope", 1)
    assert gone.value.status == 404 and not gone.value.retryable


def test_a_wrong_key_is_refused_and_busy_is_retryable(engine):
    with pytest.raises(ApiError) as refused:
        EngineClient(engine.url, "wrong").activate("w1", ["payments"], wait_ms=0)
    assert refused.value.status == 401 and not refused.value.retryable

    engine.overloaded_activations = 1
    with pytest.raises(ApiError) as busy:
        EngineClient(engine.url, "key").activate("w1", ["payments"], wait_ms=0)
    assert busy.value.status == 429 and busy.value.retryable and busy.value.reason == "OVERLOADED"
