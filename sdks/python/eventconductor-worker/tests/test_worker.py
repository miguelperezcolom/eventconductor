import threading
import time

import pytest

from eventconductor_worker import TaskError, Worker


def make_worker(engine, **kwargs):
    defaults = dict(api_key="key", worker_id="py-test", concurrency=2, lease_ms=3_000, wait_ms=200,
                    backoff_base=0.01, backoff_max=0.05, shutdown_grace=5)
    defaults.update(kwargs)
    return Worker(engine.url, **defaults)


def test_a_handler_completes_its_tasks_with_its_output(engine):
    worker = make_worker(engine)
    seen = []

    @worker.task("payments")
    def charge(task):
        seen.append((task.id, task.step_id, task.variables["amount"], task.attempt))
        return {"chargeId": "ch-" + task.id}

    engine.add("t1", "payments", {"amount": "10"})
    engine.add("t2", "payments", {"amount": "20"}, attempt=3)
    worker.start()
    try:
        engine.wait_for(lambda e: len(e.completed) == 2)
    finally:
        worker.stop()

    assert sorted(seen) == [("t1", "step", "10", 1), ("t2", "step", "20", 3)]
    assert sorted((c["id"], c["attempt"], c["variables"]["chargeId"]) for c in engine.completed) == [
        ("t1", 1, "ch-t1"), ("t2", 3, "ch-t2")]
    assert all(a["workerId"] == "py-test" and a["topics"] == ["payments"] for a in engine.activations)


def test_a_task_error_fails_with_its_code_and_retryability(engine):
    worker = make_worker(engine)

    @worker.task("payments")
    def charge(task):
        raise TaskError("CARD_INVALID", "bad number", retryable=False)

    engine.add("t1", "payments")
    worker.start()
    try:
        engine.wait_for(lambda e: e.failed)
    finally:
        worker.stop()
    assert engine.failed[0] == {"id": "t1", "attempt": 1, "code": "CARD_INVALID", "message": "bad number",
                                "retryable": False}


def test_any_other_exception_fails_the_task_retryably(engine):
    worker = make_worker(engine)

    @worker.task("payments")
    def charge(task):
        raise ValueError("boom")

    engine.add("t1", "payments")
    worker.start()
    try:
        engine.wait_for(lambda e: e.failed)
    finally:
        worker.stop()
    assert engine.failed[0]["code"] == "ValueError"
    assert engine.failed[0]["message"] == "boom"
    assert engine.failed[0]["retryable"] is True


def test_a_cancellation_arrives_through_the_heartbeat_and_nothing_is_reported(engine):
    worker = make_worker(engine, heartbeat_every=0.05)
    observed = threading.Event()

    @worker.task("payments")
    def charge(task):
        engine.cancel(task.id)
        assert task.wait_cancelled(5)
        observed.set()
        return {"never": "sent"}

    engine.add("t1", "payments")
    worker.start()
    try:
        assert observed.wait(5)
        engine.wait_for(lambda e: worker.in_flight() == 0)
    finally:
        worker.stop()
    assert engine.completed == []
    assert any(h["id"] == "t1" for h in engine.heartbeats)


def test_long_tasks_keep_their_lease_with_heartbeats(engine):
    worker = make_worker(engine, lease_ms=1_000, heartbeat_every=0.1)
    other = make_worker(engine, worker_id="other", lease_ms=1_000)
    other_seen = []

    @worker.task("payments")
    def slow(task):
        time.sleep(1.6)  # longer than the lease: only the heartbeats keep it ours
        return {}

    @other.task("payments")
    def steal(task):
        other_seen.append(task.id)
        return {}

    engine.add("t1", "payments")
    worker.start()
    engine.wait_for(lambda e: worker.in_flight() == 1)
    other.start()
    try:
        engine.wait_for(lambda e: len(e.completed) == 1)
    finally:
        worker.stop()
        other.stop()
    assert other_seen == []


def test_a_reply_the_engine_cannot_take_is_retried(engine):
    worker = make_worker(engine)
    worker.handle("payments", lambda task: {"ok": "yes"})
    engine.unavailable_replies = 2
    engine.add("t1", "payments")
    worker.start()
    try:
        engine.wait_for(lambda e: e.completed)
    finally:
        worker.stop()
    assert engine.completed[0]["variables"] == {"ok": "yes"}


def test_a_reply_the_engine_no_longer_wants_is_dropped(engine):
    worker = make_worker(engine)
    replied = threading.Event()

    @worker.task("payments")
    def charge(task):
        engine.tasks[task.id].attempt = 2  # the step ran again meanwhile: this attempt is stale
        replied.set()
        return {}

    engine.add("t1", "payments")
    worker.start()
    try:
        assert replied.wait(5)
        engine.wait_for(lambda e: worker.in_flight() == 0)
    finally:
        worker.stop()
    assert engine.completed == []


def test_the_worker_asks_only_for_what_it_has_room_for(engine):
    worker = make_worker(engine, concurrency=2)
    release = threading.Event()
    running = []

    @worker.task("payments")
    def charge(task):
        running.append(task.id)
        release.wait(5)
        return {}

    for i in range(5):
        engine.add(f"t{i}", "payments")
    worker.start()
    try:
        engine.wait_for(lambda e: len(running) == 2)
        time.sleep(0.3)
        assert len(running) == 2
        assert all(a["maxTasks"] <= 2 for a in engine.activations)
        release.set()
        engine.wait_for(lambda e: len(e.completed) == 5)
    finally:
        release.set()
        worker.stop()


def test_an_overloaded_engine_is_retried(engine):
    worker = make_worker(engine)
    worker.handle("payments", lambda task: None)
    engine.overloaded_activations = 1
    engine.add("t1", "payments")
    worker.start()
    try:
        engine.wait_for(lambda e: e.completed, timeout=10)
    finally:
        worker.stop()
    assert engine.completed[0]["variables"] == {}


def test_stop_waits_for_running_tasks(engine):
    worker = make_worker(engine)
    started = threading.Event()

    @worker.task("payments")
    def charge(task):
        started.set()
        time.sleep(0.5)
        return {"done": "yes"}

    engine.add("t1", "payments")
    worker.start()
    assert started.wait(5)
    worker.stop(grace=5)
    assert engine.completed and engine.completed[0]["variables"] == {"done": "yes"}
    assert not worker.running


def test_a_dynamic_handler_injects_then_completes(engine):
    worker = make_worker(engine)

    @worker.task("planning")
    def plan(task):
        task.inject([{"id": "extra", "type": "ACTION", "preconditionStepId": task.step_id}])
        return {}

    engine.add("t1", "planning")
    worker.start()
    try:
        engine.wait_for(lambda e: e.completed)
    finally:
        worker.stop()
    assert engine.injected[0]["steps"][0]["id"] == "extra"


def test_run_blocks_until_stopped(engine):
    worker = make_worker(engine)
    worker.handle("payments", lambda task: {})
    runner = threading.Thread(target=worker.run, kwargs={"install_signal_handlers": False})
    runner.start()
    engine.wait_for(lambda e: e.activations)
    worker._stopping.set()
    runner.join(5)
    assert not runner.is_alive()


def test_misconfiguration_is_reported_early(engine):
    with pytest.raises(ValueError):
        Worker(engine.url, concurrency=0)
    with pytest.raises(RuntimeError):
        make_worker(engine).start()
    with pytest.raises(ValueError):
        make_worker(engine).handle("", lambda task: None)
