---
title: "0 · Setup"
description: Build the workshop project, run it, and open the EventConductor console.
---

## Build the engine and the project

The workshop project lives in the EventConductor repository and builds against the engine from source:

```bash
git clone https://github.com/miguelperezcolom/eventconductor.git
cd eventconductor
mvn -B install -DskipTests -Djacoco.skip=true      # installs the engine into ~/.m2 (a few minutes)

cd examples/workshop-hotel-booking
mvn verify                                         # validates the definitions and runs the tests
```

:::tip[In a project of your own]
Copy the workshop's `pom.xml` and set `eventconductor.version` to the latest release on [Maven Central](https://central.sonatype.com/artifact/io.mateu.workflow/workflow-engine) instead of `1.0-SNAPSHOT`. Nothing else changes.
:::

## Run it

```bash
mvn spring-boot:run
```

When the log says `Started HotelBookingApplication`, open **http://localhost:8095**.

![The console: the Workflow menu over the list of definitions](/workshop/00-console-menu.jpg)

The console lists the six workflow definitions of the workshop. The **Workflow** menu leads to everything you will use: **Processes** (the runs), **Steps**, **Tasks**, **Incidents**, **Batch operations** and more.

## What is running

One Spring Boot application holds all of it — the point of this setup is that there is nothing else to install:

```java
@WorkflowEmbeddedApplication          // the engine, embedded in this app
@EnableWorkflowUi                     // its console
@EnableJpaRepositories(basePackages = "io.mateu.workflow.infra.out.persistence")
@AutoConfigurationPackage(basePackages = "io.mateu.workflow.infra.out.persistence")
public class HotelBookingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HotelBookingApplication.class, args);
    }
}
```

The essential configuration, from `application.properties`:

```properties
workflow.mode=embedded              # the engine runs inside this application
workflow.persistence=jpa            # the console reads the engine's tables…
spring.datasource.url=jdbc:h2:mem:hotel;DB_CLOSE_DELAY=-1   # …kept in an in-memory H2

# Workshop only: no login. Since 2.27/2.28 the REST API, the console and MCP refuse anonymous
# callers; this lets a laptop use them. NEVER in anything reachable from a network.
workflow.message-api.allow-anonymous=true
```

The data lives in memory: **every restart starts clean**. In production, the same engine runs on PostgreSQL, and — when one node is not enough — distributed over Kafka, with the same definitions: see [Deployment Modes](/guides/deployment-modes/).

:::note[Known issue in this setup]
The console may show a red *"No tienes permiso para esta acción"* / *"You don't have permission"* toast on every page in this anonymous setup. It is harmless for the workshop — every action used here works.
:::
