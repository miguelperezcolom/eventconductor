"""An in-process stand-in for the engine's worker API, faithful to the protocol: leases, attempts,
long-polls, heartbeats that carry cancellations, and the same statuses and reason codes."""

from __future__ import annotations

import json
import threading
import time
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional

API = "/workflow/api/workers"


@dataclass
class FakeTask:
    id: str
    topic: str
    variables: Dict[str, Optional[str]]
    attempt: int = 1
    process_id: str = "p-1"
    step_id: str = "step"
    lease_until: float = 0.0
    lease_token: Optional[str] = None
    deliveries: int = 0
    cancelled: bool = False


@dataclass
class FakeEngine:
    api_key: Optional[str] = "key"
    tasks: Dict[str, FakeTask] = field(default_factory=dict)
    completed: List[Dict[str, Any]] = field(default_factory=list)
    failed: List[Dict[str, Any]] = field(default_factory=list)
    injected: List[Dict[str, Any]] = field(default_factory=list)
    heartbeats: List[Dict[str, Any]] = field(default_factory=list)
    activations: List[Dict[str, Any]] = field(default_factory=list)
    unavailable_replies: int = 0  # the next N replies answer 503
    overloaded_activations: int = 0  # the next N activations answer 429

    def __post_init__(self) -> None:
        self.lock = threading.Condition()
        self.server: Optional[ThreadingHTTPServer] = None

    # -- test controls ----------------------------------------------------------------------------

    def add(self, task_id: str, topic: str, variables: Optional[Dict[str, Optional[str]]] = None,
            attempt: int = 1) -> None:
        with self.lock:
            self.tasks[task_id] = FakeTask(task_id, topic, dict(variables or {}), attempt, process_id="p-" + task_id)
            self.lock.notify_all()

    def cancel(self, task_id: str) -> None:
        with self.lock:
            task = self.tasks.get(task_id)
            if task is None:
                return
            if task.lease_until > time.monotonic():
                task.cancelled = True
            else:
                del self.tasks[task_id]

    def wait_for(self, predicate, timeout: float = 10.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self.lock:
                if predicate(self):
                    return
            time.sleep(0.02)
        raise AssertionError("condition not reached in time")

    @property
    def url(self) -> str:
        assert self.server is not None
        host, port = self.server.server_address[:2]
        return f"http://{host}:{port}"

    def start(self) -> "FakeEngine":
        engine = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args: Any) -> None:  # quiet
                pass

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length) or b"{}")
                if engine.api_key and self.headers.get("X-Api-Key") != engine.api_key:
                    return self._answer(401, {"detail": "Missing or invalid X-Api-Key header"})
                status, payload = engine.handle(self.path, body)
                self._answer(status, payload)

            def _answer(self, status: int, payload: Optional[Dict[str, Any]]) -> None:
                data = b"" if payload is None else json.dumps(payload).encode()
                self.send_response(status)
                if payload is not None:
                    self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def stop(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()

    # -- protocol ---------------------------------------------------------------------------------

    def handle(self, path: str, body: Dict[str, Any]):
        if path == API + "/activate":
            return self._activate(body)
        parts = path[len(API) + len("/tasks/"):].split("/")
        task_id, action = parts[0], parts[1]
        with self.lock:
            task = self.tasks.get(task_id)
            if action == "heartbeat":
                self.heartbeats.append({"id": task_id, **body})
                if task is None:
                    return 404, {"reason": "NOT_FOUND", "detail": "gone"}
                if task.cancelled:
                    del self.tasks[task_id]
                    return 200, {"cancelled": True, "deadline": None}
                if body.get("leaseToken") != task.lease_token:
                    return 409, {"reason": "LEASE_LOST", "detail": "re-leased"}
                task.lease_until = time.monotonic() + body.get("extendMs", 60_000) / 1000.0
                return 200, {"cancelled": False, "deadline": "2026-01-01T00:00:00Z"}
            if self.unavailable_replies > 0:
                self.unavailable_replies -= 1
                return 503, {"reason": "UNAVAILABLE", "detail": "busy"}
            if task is None:
                return 404, {"reason": "NOT_FOUND", "detail": "gone"}
            if body.get("attempt") != task.attempt:
                return 409, {"reason": "STALE_ATTEMPT", "detail": "stale"}
            if task.cancelled:
                del self.tasks[task_id]
                return 409, {"reason": "CANCELLED", "detail": "cancelled"}
            if action == "inject":
                self.injected.append({"id": task_id, **body})
                return 204, None
            del self.tasks[task_id]
            (self.completed if action == "complete" else self.failed).append({"id": task_id, **body})
            self.lock.notify_all()
            return 204, None

    def _activate(self, body: Dict[str, Any]):
        with self.lock:
            self.activations.append(body)
            if self.overloaded_activations > 0:
                self.overloaded_activations -= 1
                return 429, {"reason": "OVERLOADED", "detail": "too many"}
            deadline = time.monotonic() + min(body.get("waitMs", 0), 2_000) / 1000.0
            while True:
                now = time.monotonic()
                free = [t for t in self.tasks.values()
                        if t.topic in body["topics"] and not t.cancelled and t.lease_until <= now]
                if free or now >= deadline:
                    break
                self.lock.wait(deadline - now)
            leased = []
            for task in free[: body.get("maxTasks", 1)]:
                task.lease_until = time.monotonic() + body.get("leaseMs", 60_000) / 1000.0
                task.lease_token = uuid.uuid4().hex
                task.deliveries += 1
                leased.append({"id": task.id, "processId": task.process_id, "workflowDefinitionId": "wd",
                               "stepId": task.step_id, "taskId": "", "topic": task.topic,
                               "variables": task.variables, "attempt": task.attempt, "delivery": task.deliveries,
                               "leaseToken": task.lease_token, "deadline": "2026-01-01T00:00:00Z"})
            return 200, {"tasks": leased}
