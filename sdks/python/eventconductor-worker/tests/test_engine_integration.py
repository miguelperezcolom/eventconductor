"""Against a real engine: set EC_ENGINE_URL (and EC_API_KEY, EC_TOPIC) and start a process whose
ACTION step is on EC_TOPIC; this test serves it. Skipped otherwise - the fake engine covers the
protocol in every build."""

import os

import pytest

from eventconductor_worker import Worker

ENGINE = os.environ.get("EC_ENGINE_URL")


@pytest.mark.skipif(not ENGINE, reason="EC_ENGINE_URL is not set")
def test_serves_a_task_of_a_real_engine():
    import threading

    served = threading.Event()
    worker = Worker(ENGINE, api_key=os.environ.get("EC_API_KEY"), worker_id="py-integration", wait_ms=5_000)

    @worker.task(os.environ.get("EC_TOPIC", "payments"))
    def handle(task):
        served.set()
        return {"servedBy": "python"}

    worker.start()
    try:
        assert served.wait(float(os.environ.get("EC_WAIT_SECONDS", "60"))), "no task arrived"
    finally:
        worker.stop()
