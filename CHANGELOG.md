# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0-beta.006] - 2026-07-02

### Added
- Spring Boot Actuator with Prometheus, health, and info endpoints in
  `workflow-engine` and `forms-engine`.
- Custom Micrometer metrics for processes by status, step executions and
  retries.
- Spring Security with HTTP Basic on REST endpoints, configurable via
  application properties. Actuator health/info remain public.
- Flyway migrations for the workflow and forms schemas. Default `ddl-auto`
  is now `validate` in production profiles.
- Logstash-encoder structured JSON logging via `application-prod.yaml`.
- LICENSE (MIT), SECURITY.md, CONTRIBUTING.md, CODE_OF_CONDUCT.md and
  CHANGELOG.md.
- Dependabot configuration for Maven, GitHub Actions, Docker and npm.
- Build status, CodeQL and license badges in the README.
- Helm chart hardening: external secrets, NetworkPolicy, PodDisruptionBudget,
  Ingress template and Actuator-based probes.
- New PR CI workflow that runs `mvn verify` (unit + integration tests +
  JaCoCo coverage check) and Trivy container scans.
- Renamed misspelled `buid-and-publish.yml` to `build-and-publish.yml` and
  enabled running the test suite during release builds.

### Changed
- `workflow.mode` now defaults to `embedded` (previously `kafka`), mirroring
  `workflow.persistence` which defaults to `memory`. Apps start fully
  in-process with no external dependencies and opt into JPA/Kafka as they
  scale. The standalone distributed apps set `kafka`/`jpa` explicitly.
- Bumped the Mateu UI dependency to `3.0-alpha.222`.
- Dockerfiles run as a non-root `app` user (UID 10001).
- `docker-compose.yml` split into `docker-compose.dev.yml` (with default
  passwords) and `docker-compose.yml` (with required env vars).

### Fixed
- Quote the reserved SQL column `values` in `FormExecutionEntity` so schema
  creation succeeds on H2 and PostgreSQL.

### Security
- All REST endpoints in the orchestrator and forms standalone apps now
  require authentication by default.
- Bumped Eclipse JGit to `6.10.1.202505221210-r` to fix the XXE vulnerability
  CVE-2025-4949 (GHSA-vrpq-qp53-qv56).

## [Earlier]

EventConductor pre-1.0 snapshots. See the `git log` for individual commits.
