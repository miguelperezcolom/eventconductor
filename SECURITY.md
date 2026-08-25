# Security Policy

## Supported Versions

Only the latest released version of EventConductor receives security updates.
We recommend always upgrading to the most recent release.

| Version | Supported |
|---------|-----------|
| latest  | ✅        |
| < latest | ❌       |

## Reporting a Vulnerability

If you discover a security vulnerability in EventConductor, **please do not
open a public GitHub issue**. Instead, report it privately so we can fix it
before the details become public.

Preferred channel: **GitHub Security Advisories** —
<https://github.com/miguelperezcolom/eventconductor/security/advisories/new>

**Not on Discord.** The `#eventconductor` channel is public: posting a vulnerability there
discloses it to everyone in the room before there is a fix, which is the one outcome this policy
exists to avoid.

Alternatively, you may email the maintainer directly. The address is
available from the project's `pom.xml` and GitHub profile.

Please include:

- A description of the vulnerability and the affected component
  (workflow-engine, forms-engine, MCP server, IA agent, etc.)
- Steps to reproduce or a proof-of-concept
- The version / commit you tested against
- Your assessment of the impact

We will acknowledge receipt within 72 hours and aim to publish a fix within
30 days for high-severity issues. Coordinated disclosure is appreciated:
we will credit you in the release notes unless you ask otherwise.

## What is tested, and what that means

The engine treats **every definition and every input as untrusted**: a workflow, form or rule file
arrives from a git import, a watched directory or an editor, and a business key, a variable or a
submitted form value arrives from an upstream record, the REST message API, an MCP call or a
browser. The behaviour that follows from that is pinned by an adversarial test suite, specified
test by test in [TESTING.md §8](TESTING.md) and run in CI on every change:

- **Expression sandbox** — every JEXL expression (step guards, link conditions, correlation
  expressions, rule expressions) runs under `JexlPermissions.RESTRICTED` with loops, lambdas,
  instantiation and global assignment denied, and behind a length and nesting ceiling. Reflection
  reaches nothing, an expression cannot spin an orchestration thread, and an oversized one fails as
  an ordinary exception rather than unwinding the thread that was evaluating it.
- **Definitions** — graph invariants (duplicate ids, dangling references, cycles, unreachable
  steps), the document validated as written so a misspelled key cannot be silently ignored, and a
  runtime cap on how deeply PROCESS steps may nest, so two workflows that start each other cannot
  fan out unbounded.
- **Input** — a business key or variable that reads as SQL is a value and not a statement; values
  round-trip byte for byte, without being escaped or truncated on the way in; forms accept only the
  fields they declare and enforce their own `required`.
- **Size** — nothing is truncated, and nothing is unbounded either. One value, one identifier, the
  variables of one event and the bytes of one request each clear a ceiling set far above any real
  payload (`InputLimits`, `workflow.rest.max-body-bytes`), because a value cut to fit a column is
  worse than one refused while a caller who can decide how much memory the engine spends is worse
  than both. Identifiers are held to 255 for a different reason — that is the width of the columns
  that hold them, and an over-long key otherwise fails inside the transaction saving a running
  process. What happens to a refusal depends on the door: a Kafka record is parked on the
  dead-letter destination, a REST caller is answered 400, a form is answered on the page.
- **The doors** — the message API's key comparison, the git webhook's per-provider signature
  verification, malformed and deeply nested bodies, oversized ones, and unknown providers, are all
  asserted through the HTTP layer rather than against the controller objects.
- **Flow authorization** — a workflow declares the scopes and roles a caller must hold to *start* a
  process of it, and a form declares the ones a person must hold to *see, claim and complete* a task
  of it. Requires-all and fail-closed: a caller nobody could identify holds nothing, so anything
  required refuses them. Where the identity comes from is a port with a default that reads either an
  application-authenticated login or a gateway-forwarded token, verified beating asserted. Off unless
  `workflow.security.flow-authorization.enabled`. The task listing is narrowed as a convenience, not
  as the boundary — claiming and completing are refused on their own account, so a task id obtained
  some other way buys nothing.
- **The browser** — because values are stored as sent, whether a payload stays inert is a property
  of the rendering. Stored-XSS journeys run in a real Chromium, each with a control proving the
  payload actually reached the page.

Several of these tests were written against a real defect and are green because it was fixed; those
are marked as such in TESTING.md. None of this is a claim that the engine is unbreakable — it is a
record of what has been tried, so that a report of something new is a report of something new.

## Out of scope

- Vulnerabilities in third-party dependencies that are already tracked by
  Dependabot — please open a PR with the upgrade instead.
- Issues that require an attacker with administrative access to the host,
  database, or Kafka broker.
- Anything in the `demo/` and `testbench/` modules: they are example code
  and are not intended for production use. In particular, the `demo/api-gw`
  module ships a **throwaway self-signed keystore** (`keystore.p12`) with a
  well-known password, committed only so the demo runs out of the box. It is
  not a secret and must never be reused in a real deployment — supply your
  own keystore and password there.
