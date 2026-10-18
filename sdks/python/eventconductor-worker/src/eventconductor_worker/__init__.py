"""Serve EventConductor tasks from Python over HTTP long-polling.

    from eventconductor_worker import Worker, TaskError

    worker = Worker("http://localhost:8080", api_key="...")

    @worker.task("payments")
    def charge(task):
        return {"chargeId": "ch-1"}

    worker.run()
"""

from .client import ApiError, EngineClient, TaskGone
from .worker import Task, TaskError, Worker

__all__ = ["ApiError", "EngineClient", "Task", "TaskError", "TaskGone", "Worker"]
__version__ = "0.1.0"
