"""A worker that serves EventConductor tasks over HTTP: long-poll, run, heartbeat, answer."""

from __future__ import annotations

import logging
import random
import signal
import socket
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Mapping, Optional

from .client import ApiError, EngineClient, TaskGone

log = logging.getLogger("eventconductor_worker")

Handler = Callable[["Task"], Optional[Mapping[str, Any]]]


class TaskError(Exception):
    """A declared business failure: fails the step with ``code`` (and ``message``) as the reason.

    ``retryable=False`` fails the step at once, without spending its retries - for a failure
    retrying cannot fix (a 400 from a downstream API, an invalid card).
    """

    def __init__(self, code: str, message: Optional[str] = None, retryable: bool = True) -> None:
        super().__init__(message or code)
        self.code = code
        self.message = message
        self.retryable = retryable


@dataclass
class Task:
    """A task leased to this worker.

    ``variables`` are the process variables as the engine holds them (strings; parse JSON yourself
    when a value is structured). Handlers must be idempotent: a task whose lease expired - this
    worker paused, crashed, lost the network - is handed to another worker.
    """

    id: str
    process_id: str
    workflow_definition_id: Optional[str]
    step_id: str
    task_id: Optional[str]
    topic: str
    variables: Dict[str, Optional[str]]
    attempt: Optional[int]
    delivery: int
    lease_token: str
    deadline: Optional[str]
    _cancelled: threading.Event = field(default_factory=threading.Event, repr=False)
    _client: Optional[EngineClient] = field(default=None, repr=False)

    @classmethod
    def from_json(cls, data: Mapping[str, Any], client: Optional[EngineClient] = None) -> "Task":
        return cls(
            id=data["id"],
            process_id=data.get("processId") or "",
            workflow_definition_id=data.get("workflowDefinitionId"),
            step_id=data.get("stepId") or "",
            task_id=data.get("taskId"),
            topic=data.get("topic") or "",
            variables=dict(data.get("variables") or {}),
            attempt=data.get("attempt"),
            delivery=int(data.get("delivery") or 1),
            lease_token=data.get("leaseToken") or "",
            deadline=data.get("deadline"),
            _client=client,
        )

    @property
    def cancelled(self) -> bool:
        """True once the engine cancelled the task (or it was handed to someone else): stop working."""
        return self._cancelled.is_set()

    def wait_cancelled(self, timeout: Optional[float] = None) -> bool:
        """Blocks until the task is cancelled or ``timeout`` seconds pass; returns whether it was."""
        return self._cancelled.wait(timeout)

    def inject(self, steps: List[Mapping[str, Any]]) -> None:
        """Injects steps into the process (DYNAMIC steps only); return from the handler to complete."""
        if self._client is None:
            raise RuntimeError("This task is not bound to an engine client")
        self._client.inject(self.id, self.attempt, steps)


@dataclass
class _InFlight:
    task: Task
    next_beat: float


class Worker:
    """Long-polls the engine for the topics it has handlers for and runs them on a thread pool.

    :param base_url: the engine's root URL
    :param api_key: ``X-Api-Key``: a worker credential or the engine's api key
    :param worker_id: how the engine names this worker (default: host name + a random suffix)
    :param concurrency: how many tasks run at once; the worker only asks for as many as it has room for
    :param lease_ms: how long a task is ours without a heartbeat
    :param wait_ms: how long one long-poll waits for tasks (capped by the engine)
    :param heartbeat_every: seconds between heartbeats of a running task (default: a third of the lease)
    :param max_reply_attempts: how many times a reply is sent before it is given up on
    :param backoff_base: first retry delay in seconds; doubles each time, with jitter, up to ``backoff_max``
    :param shutdown_grace: seconds ``stop`` waits for running tasks
    """

    def __init__(self, base_url: str, api_key: Optional[str] = None, *, worker_id: Optional[str] = None,
                 concurrency: int = 4, lease_ms: int = 60_000, wait_ms: int = 20_000,
                 heartbeat_every: Optional[float] = None, max_reply_attempts: int = 8,
                 backoff_base: float = 0.5, backoff_max: float = 30.0, shutdown_grace: float = 30.0,
                 client: Optional[EngineClient] = None) -> None:
        if concurrency < 1:
            raise ValueError("concurrency must be at least 1")
        self.client = client or EngineClient(base_url, api_key)
        self.worker_id = worker_id or f"{socket.gethostname()}-{uuid.uuid4().hex[:8]}"
        self.concurrency = concurrency
        self.lease_ms = lease_ms
        self.wait_ms = wait_ms
        self.heartbeat_every = heartbeat_every if heartbeat_every is not None else max(0.05, lease_ms / 3000.0)
        self.max_reply_attempts = max(1, max_reply_attempts)
        self.backoff_base = backoff_base
        self.backoff_max = backoff_max
        self.shutdown_grace = shutdown_grace
        self._handlers: Dict[str, Handler] = {}
        self._in_flight: Dict[str, _InFlight] = {}
        self._lock = threading.Condition()
        self._stopping = threading.Event()
        self._executor: Optional[ThreadPoolExecutor] = None
        self._threads: List[threading.Thread] = []

    # -- registration ---------------------------------------------------------------------------

    def task(self, topic: str) -> Callable[[Handler], Handler]:
        """Registers the decorated function as the handler of ``topic``'s tasks.

        The handler receives a :class:`Task` and returns the output variables (a dict, or None).
        Raise :class:`TaskError` for a declared failure; any other exception fails the task as
        retryable, with the exception as the reason.
        """

        def register(handler: Handler) -> Handler:
            self.handle(topic, handler)
            return handler

        return register

    def handle(self, topic: str, handler: Handler) -> None:
        if not topic:
            raise ValueError("topic is required")
        self._handlers[topic] = handler

    # -- lifecycle ------------------------------------------------------------------------------

    def start(self) -> None:
        """Starts polling in background threads; returns at once."""
        if not self._handlers:
            raise RuntimeError("No handlers registered: decorate a function with @worker.task('<topic>')")
        if self._executor is not None:
            return
        self._stopping.clear()
        self._executor = ThreadPoolExecutor(max_workers=self.concurrency, thread_name_prefix="ec-task")
        for name, target in (("ec-poller", self._poll_loop), ("ec-heartbeats", self._heartbeat_loop)):
            thread = threading.Thread(target=target, name=name, daemon=True)
            thread.start()
            self._threads.append(thread)
        log.info("Worker %s serving topics %s at %s", self.worker_id, sorted(self._handlers), self.client.base_url)

    def run(self, install_signal_handlers: bool = True) -> None:
        """Starts and blocks until :meth:`stop` (SIGINT/SIGTERM call it when ``install_signal_handlers``)."""
        if install_signal_handlers and threading.current_thread() is threading.main_thread():
            for signum in (signal.SIGINT, signal.SIGTERM):
                signal.signal(signum, lambda *_: self._stopping.set())
        self.start()
        try:
            while not self._stopping.wait(0.5):
                pass
        finally:
            self.stop()

    def stop(self, grace: Optional[float] = None) -> None:
        """Stops asking for tasks and waits up to ``grace`` seconds for the running ones to finish.

        A long-poll already under way is let finish (up to ``wait_ms``); tasks it brings are run.
        """
        self._stopping.set()
        with self._lock:
            self._lock.notify_all()
        deadline = time.monotonic() + (self.shutdown_grace if grace is None else grace)
        for thread in self._threads:
            if thread.name == "ec-poller":
                thread.join(max(0.0, deadline - time.monotonic()))
        executor, self._executor = self._executor, None
        if executor is not None:
            with self._lock:
                while self._in_flight and time.monotonic() < deadline:
                    self._lock.wait(max(0.01, min(0.5, deadline - time.monotonic())))
                abandoned = len(self._in_flight)
            if abandoned:
                log.warning("%d task(s) still running after the shutdown grace; their leases will expire "
                            "and the engine will hand them to another worker", abandoned)
            executor.shutdown(wait=False)
        for thread in self._threads:
            thread.join(0.1)
        self._threads = []

    @property
    def running(self) -> bool:
        return self._executor is not None and not self._stopping.is_set()

    def in_flight(self) -> int:
        with self._lock:
            return len(self._in_flight)

    # -- polling --------------------------------------------------------------------------------

    def _poll_loop(self) -> None:
        failures = 0
        while not self._stopping.is_set():
            with self._lock:
                while len(self._in_flight) >= self.concurrency and not self._stopping.is_set():
                    self._lock.wait(0.5)
                room = self.concurrency - len(self._in_flight)
            if self._stopping.is_set():
                return
            try:
                tasks = self.client.activate(self.worker_id, list(self._handlers), max_tasks=room,
                                             lease_ms=self.lease_ms, wait_ms=self.wait_ms)
                failures = 0
            except ApiError as error:
                failures += 1
                delay = 1.0 if error.status == 429 else self._backoff(failures)
                log.warning("Activation refused (%s); retrying in %.1fs", error, delay)
                self._stopping.wait(delay)
                continue
            except Exception as error:  # noqa: BLE001 - the engine may be unreachable: keep trying
                failures += 1
                delay = self._backoff(failures)
                log.warning("Activation failed (%s); retrying in %.1fs", error, delay)
                self._stopping.wait(delay)
                continue
            for data in tasks:
                self._dispatch(Task.from_json(data, self.client))

    def _dispatch(self, task: Task) -> None:
        executor = self._executor
        if executor is None:
            log.warning("Task %s arrived after shutdown; its lease will expire and it will be redelivered", task.id)
            return
        with self._lock:
            self._in_flight[task.id] = _InFlight(task, time.monotonic() + self.heartbeat_every)
        executor.submit(self._run, task)

    # -- running --------------------------------------------------------------------------------

    def _run(self, task: Task) -> None:
        try:
            handler = self._handlers.get(task.topic)
            if handler is None:
                self._reply(task, "fail", lambda: self.client.fail(
                    task.id, task.attempt, "NO_HANDLER", f"No handler for topic {task.topic!r}", True))
                return
            try:
                output = handler(task)
            except TaskError as failure:
                if not task.cancelled:
                    self._reply(task, "fail", lambda: self.client.fail(
                        task.id, task.attempt, failure.code, failure.message, failure.retryable))
                return
            except Exception as error:  # noqa: BLE001 - any handler error fails the task, retryably
                log.exception("Handler for task %s (step %s) failed", task.id, task.step_id)
                if not task.cancelled:
                    self._reply(task, "fail", lambda: self.client.fail(
                        task.id, task.attempt, type(error).__name__, str(error) or repr(error), True))
                return
            if task.cancelled:
                log.info("Task %s was cancelled while it ran; not reporting it done", task.id)
                return
            variables = dict(output or {})
            self._reply(task, "complete", lambda: self.client.complete(task.id, task.attempt, variables))
        finally:
            with self._lock:
                self._in_flight.pop(task.id, None)
                self._lock.notify_all()

    def _reply(self, task: Task, what: str, send: Callable[[], None]) -> bool:
        """Sends a reply, retrying what may succeed later; gives up on what never will."""
        for attempt in range(1, self.max_reply_attempts + 1):
            try:
                send()
                return True
            except TaskGone as gone:
                log.info("The engine no longer wants the %s of task %s (%s); dropped", what, task.id, gone)
                return False
            except ApiError as error:
                if not error.retryable:
                    log.error("The engine refused the %s of task %s: %s", what, task.id, error)
                    return False
                failure: Exception = error
            except Exception as error:  # noqa: BLE001 - network trouble: retry
                failure = error
            if attempt < self.max_reply_attempts:
                delay = self._backoff(attempt)
                log.warning("Could not send the %s of task %s (%s); retry %d in %.1fs", what, task.id, failure,
                            attempt, delay)
                time.sleep(delay)
        log.error("Gave up sending the %s of task %s after %d attempts; the lease will expire and the task "
                  "be redelivered", what, task.id, self.max_reply_attempts)
        return False

    # -- heartbeats -----------------------------------------------------------------------------

    def _heartbeat_loop(self) -> None:
        while not (self._stopping.is_set() and not self._in_flight):
            now = time.monotonic()
            with self._lock:
                due = [entry for entry in self._in_flight.values() if entry.next_beat <= now]
                for entry in due:
                    entry.next_beat = now + self.heartbeat_every
            for entry in due:
                self._beat(entry.task)
            time.sleep(min(0.25, self.heartbeat_every))

    def _beat(self, task: Task) -> None:
        if task.cancelled:
            return
        try:
            answer = self.client.heartbeat(task.id, task.lease_token, self.lease_ms)
            if answer.get("cancelled"):
                log.info("Task %s was cancelled by the engine", task.id)
                task._cancelled.set()
        except TaskGone as gone:
            log.info("Task %s is no longer ours (%s)", task.id, gone)
            task._cancelled.set()
        except Exception as error:  # noqa: BLE001 - keep beating; the lease has slack
            log.warning("Heartbeat of task %s failed: %s", task.id, error)

    def _backoff(self, attempt: int) -> float:
        delay = min(self.backoff_max, self.backoff_base * (2 ** (attempt - 1)))
        return delay * (0.5 + random.random() / 2)
