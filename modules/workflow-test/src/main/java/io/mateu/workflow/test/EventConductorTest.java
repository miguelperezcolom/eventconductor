package io.mateu.workflow.test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A JUnit 5 test of workflow definitions: boots the EventConductor engine in memory (embedded mode,
 * memory persistence, no server, no broker, no database), with a clock only the test moves, every
 * task answered by the test's mocks, and a {@link WorkflowTestRuntime} to drive it.
 *
 * <pre>{@code
 * @EventConductorTest
 * class BookingSagaTest {
 *
 *     @Test
 *     void cancelsAndRefundsWhenThePaymentNeverArrives(WorkflowTestRuntime runtime) {
 *         runtime.whenTask("charge-card").thenComplete(Map.of("chargeId", "ch_1"));
 *         runtime.whenTask("refund").thenComplete();
 *
 *         var booking = runtime.start("booking", "B-1", Map.of("amount", 120));
 *         runtime.advanceTime(Duration.ofDays(3));
 *
 *         assertThat(booking).isCompensated().hasPassedSteps("charge", "refund");
 *     }
 * }
 * }</pre>
 *
 * Definitions are read from {@code classpath:/workflows/} like in any embedded application; add
 * others with {@link #definitions()}. The Spring context is cached across test classes with the
 * same configuration, as Spring caches any test context: each test starts with no mocks and a clean
 * record, the clock carries on from where the previous test left it, and processes of earlier tests
 * are left alone (their tasks never reach this test's mocks).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "workflow.mode=embedded",
        "workflow.persistence=memory",
        // The kit drives every scheduler itself; nothing ticks on real time.
        "workflow.schedulers.background=false",
        // Tasks run on the calling thread, so a call returns once the engine is done.
        "workflow.embedded.worker-threads=0",
        "spring.main.web-application-type=none",
        "spring.main.banner-mode=off"
})
@Import(WorkflowTestConfiguration.class)
@ExtendWith(EventConductorExtension.class)
public @interface EventConductorTest {

    /**
     * The application to boot: by default the engine alone ({@link WorkflowTestApplication}). Name
     * your own embedded application, or add configuration classes next to
     * {@link WorkflowTestApplication}, to have real {@code TaskHandler}s answer the tasks no mock does.
     */
    @AliasFor(annotation = SpringBootTest.class, attribute = "classes")
    Class<?>[] classes() default WorkflowTestApplication.class;

    /** Extra Spring properties ({@code key=value}), on top of — and winning over — the kit's. */
    @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
    String[] properties() default {};

    /**
     * Workflow definitions to import before the tests run, besides those under
     * {@code classpath:/workflows/}: files or directories, relative to the working directory (the
     * module, under Maven), or {@code classpath:} locations.
     */
    String[] definitions() default {};
}
