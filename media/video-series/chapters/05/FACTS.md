# Chapter 5 — facts checked (against v2.41.0)

| Claim | Source |
|---|---|
| `HTTP_CALL` calls an endpoint and maps the response, no worker | `doc/.../guides/http-calls.md` 6 |
| Named connection; base URL/credentials in configuration | `04-rooms-booking-http.ec.yaml` 25; `application.properties` 27–31; `workshop/04-calling-the-hotel.md` |
| Path/headers/body are templates; each forEach iteration sees `room` | `04-calling-the-hotel.md` (bullet "The forEach sits on the HTTP_CALL") |
| Idempotency-Key from business data; the hotel books once | `04-rooms-booking-http.ec.yaml` 28–31; `FakeHotelApi.java` (honours the key); filmed `hotel-holds` |
| `output` = JEXL over `{status, headers, body}` | `http-calls.md` 27, 51 |
| `successStatus` default any 2xx; other status fails with `HTTP_<status>` + start of body | `http-calls.md` 51–52 |
| `retryOn` default `[5xx, io]`, a 4xx not retried; retries with backoff | `http-calls.md` 44, 53 |
| Same `Idempotency-Key` (step execution id) on every retry when none is given | `http-calls.md` 54 |
| Penthouse → 409, not retried, rooms cancelled with DELETE | `FakeHotelApi.java`; `04-calling-the-hotel.md` (Run it); filmed Errors tab |
| `${secret:NAME}` by reference only, resolved in the worker; a literal fails the build | `http-calls.md` 209 |
| Since 2.27 only names under `workflow.http.secrets` | CHANGELOG 2.27.0 l. 427; `http-calls.md` 210 |
| Workers over HTTP in any language (`workflow.workers.http.topics`, long poll) | CHANGELOG 2.30.0 l. 324 |
| Python SDK in the repo, **not on PyPI yet** | CHANGELOG 2.30.0 l. 325; `sdks/python/eventconductor-worker/README.md` |

Not shown (gap): per-attempt retries in the Steps tab — the workshop's stand-in hotel never fails transiently and the
Steps tab has no attempts column; retries are explained (HTML scene), not filmed.
