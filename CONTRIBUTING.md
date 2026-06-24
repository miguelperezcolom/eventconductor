# Contributing to EventConductor

Thanks for your interest in contributing! This document explains how to set
up the project locally, what we expect in pull requests, and how the
codebase is organized.

## Getting started

### Prerequisites

- JDK 21
- Maven 3.9+
- Docker (optional, only for the full-distributed mode with Kafka + Postgres)
- Node.js 20+ (optional, only if you want to build the `doc/` site)

### Build

```bash
mvn clean install
```

This runs the full unit-test suite and produces all module jars and the
standalone app uber-jars.

### Run locally (fully embedded — no external dependencies)

```bash
cd apps/orchestrator-standalone-app
WORKFLOW_MODE=embedded WORKFLOW_PERSISTENCE=memory mvn spring-boot:run
```

### Run locally (full distributed)

```bash
docker compose -f apps/docker-compose.yml up -d   # Postgres + Redpanda
cd apps/orchestrator-standalone-app
mvn spring-boot:run
```

## Repository layout

See the top-level `README.md` for a complete map. Brief summary:

- `modules/` — reusable library jars (`workflow-engine`, `forms-engine`,
  `shared`, `sample-worker`)
- `apps/` — runnable Spring Boot standalone applications
- `demo/` — example microservices showing how to consume the libraries
- `testbench/` — minimal apps used as fixtures in tests
- `doc/` — Astro Starlight documentation site
- `charts/eventconductor` — Helm chart for Kubernetes deployment

## Coding conventions

- Java 21 with Lombok where it reduces boilerplate (entities, DTOs,
  builders). Do not Lombok-ize use cases or domain logic.
- DDD / hexagonal architecture: keep domain code in `domain/`, use cases in
  `application/usecases/`, adapters in `infra/in/*` and `infra/out/*`.
- Domain events implement `DomainEvent` and live next to the aggregate.
- Public API of a module is documented in its own module-level README.
- Public APIs MUST NOT depend on Spring annotations (we want the engine to
  be embeddable in any JVM app).

## Tests

- Unit tests use JUnit 5 + AssertJ and live next to the class they cover.
- Integration tests (`*IT.java`) run via `maven-failsafe-plugin` during
  `mvn verify`.
- We aim for ≥85% line coverage on the engine modules. JaCoCo enforces this
  on every build — see `pom.xml`.

## Pull request checklist

Before opening a PR:

- [ ] `mvn clean verify` passes locally
- [ ] New code is covered by tests
- [ ] Public API changes are reflected in the relevant `README.md`
- [ ] `CHANGELOG.md` has a one-line entry under `[Unreleased]`
- [ ] No secrets, API keys or personal credentials in commits

## Releases

Releases are cut from `main` by pushing a `vX.Y.Z` tag. The `Build and publish`
GitHub Actions workflow then:

1. Sets the Maven version from the tag.
2. Publishes the jars to Maven Central.
3. Builds and pushes the Docker images.

Update `CHANGELOG.md` (`[Unreleased]` → `[X.Y.Z] - YYYY-MM-DD`) in the same
commit that creates the tag.

## Code of conduct

By participating in this project you agree to abide by our
[Code of Conduct](./CODE_OF_CONDUCT.md).
