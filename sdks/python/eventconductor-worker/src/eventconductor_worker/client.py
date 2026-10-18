"""The HTTP worker protocol, one call per endpoint - no retries, no threads (see ``Worker`` for those)."""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any, Dict, Iterable, List, Mapping, Optional

API_PATH = "/workflow/api/workers"


class ApiError(Exception):
    """The engine answered with an error status.

    ``status`` is the HTTP status, ``reason`` the engine's reason code when it gave one
    (``STALE_ATTEMPT``, ``LEASE_LOST``, ``CANCELLED``, ``OVERLOADED``, ...).
    """

    def __init__(self, status: int, reason: Optional[str], detail: str) -> None:
        super().__init__(f"HTTP {status}{' ' + reason if reason else ''}: {detail}")
        self.status = status
        self.reason = reason
        self.detail = detail

    @property
    def retryable(self) -> bool:
        """Whether sending the same request again may succeed (the engine was busy or down)."""
        return self.status == 429 or self.status >= 500


class TaskGone(ApiError):
    """404 or 409: the engine no longer wants an answer for this task - it was answered already,
    cancelled, re-leased to another worker, or replaced by a newer attempt. Drop it."""


class EngineClient:
    """A thin client of ``/workflow/api/workers``.

    :param base_url: the engine's root URL, e.g. ``http://localhost:8080``
    :param api_key: sent as ``X-Api-Key`` - a worker credential (``workflow.workers.http.api-keys``)
        or the engine's api key
    :param timeout: seconds for every call but ``activate``, whose timeout follows its ``wait_ms``
    """

    def __init__(self, base_url: str, api_key: Optional[str] = None, timeout: float = 30.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def activate(self, worker_id: str, topics: Iterable[str], max_tasks: int = 1, lease_ms: int = 60_000,
                 wait_ms: int = 20_000) -> List[Dict[str, Any]]:
        """Long-polls for up to ``max_tasks`` tasks of ``topics``, waiting up to ``wait_ms``.

        Returns the leased tasks (possibly none) as the engine sent them.
        """
        body = {"workerId": worker_id, "topics": list(topics), "maxTasks": max_tasks, "leaseMs": lease_ms,
                "waitMs": wait_ms}
        answer = self._post("/activate", body, timeout=wait_ms / 1000.0 + 15.0)
        return list((answer or {}).get("tasks") or [])

    def complete(self, task_id: str, attempt: Optional[int], variables: Optional[Mapping[str, Any]] = None) -> None:
        self._post(f"/tasks/{_quote(task_id)}/complete", {"attempt": attempt, "variables": dict(variables or {})})

    def fail(self, task_id: str, attempt: Optional[int], code: str, message: Optional[str] = None,
             retryable: bool = True) -> None:
        self._post(f"/tasks/{_quote(task_id)}/fail",
                   {"attempt": attempt, "code": code, "message": message, "retryable": retryable})

    def heartbeat(self, task_id: str, lease_token: str, extend_ms: int) -> Dict[str, Any]:
        """Extends the lease. The answer says ``cancelled: true`` when the engine cancelled the task."""
        return self._post(f"/tasks/{_quote(task_id)}/heartbeat",
                          {"leaseToken": lease_token, "extendMs": extend_ms}) or {}

    def inject(self, task_id: str, attempt: Optional[int], steps: List[Mapping[str, Any]]) -> None:
        """Injects steps into the process of a DYNAMIC step (complete the task afterwards)."""
        self._post(f"/tasks/{_quote(task_id)}/inject", {"attempt": attempt, "steps": list(steps)})

    def _post(self, path: str, body: Mapping[str, Any], timeout: Optional[float] = None) -> Any:
        data = json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base_url + API_PATH + path, data=data, method="POST")
        request.add_header("Content-Type", "application/json")
        request.add_header("Accept", "application/json")
        if self.api_key:
            request.add_header("X-Api-Key", self.api_key)
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            raise _api_error(error) from None
        if not raw:
            return None
        return json.loads(raw.decode("utf-8"))


def _api_error(error: urllib.error.HTTPError) -> ApiError:
    reason: Optional[str] = None
    detail = error.reason if isinstance(error.reason, str) else str(error.reason)
    try:
        payload = json.loads(error.read().decode("utf-8") or "{}")
        reason = payload.get("reason")
        detail = payload.get("detail") or payload.get("message") or detail
    except Exception:  # noqa: BLE001 - an unreadable error body still has a status
        pass
    kind = TaskGone if error.code in (404, 409) else ApiError
    return kind(error.code, reason, str(detail))


def _quote(value: str) -> str:
    return urllib.request.quote(value, safe="")
