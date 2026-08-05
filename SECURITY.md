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
