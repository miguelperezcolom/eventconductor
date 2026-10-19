/**
 * EventConductor workflow test kit: boot the engine in memory inside a JUnit 5 test, drive your own
 * workflow definitions through it, mock their tasks, move the clock, and assert on the result.
 *
 * <p>Start with {@link io.mateu.workflow.test.EventConductorTest} and
 * {@link io.mateu.workflow.test.WorkflowTestRuntime}.
 *
 * <p>Every embedded EventConductor application component-scans {@code io.mateu.workflow}, which
 * includes this package. Nothing here is picked up by such a scan: the kit's configuration
 * classes are {@code @TestConfiguration} / {@code @TestComponent}, which Spring Boot's test
 * exclude filter keeps out of component scanning — they are in a context only when a test names
 * them (through {@link io.mateu.workflow.test.EventConductorTest}).
 */
package io.mateu.workflow.test;
