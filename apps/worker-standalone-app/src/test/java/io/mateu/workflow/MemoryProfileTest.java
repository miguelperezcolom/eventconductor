package io.mateu.workflow;

import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.application.TaskOverrideStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code memory} profile: a worker with no database at all, which is the shape a CI suite wants
 * — one container, no volume — and the one the guide recommends for it.
 *
 * <p>It did not start. The stores are conditional, but the Spring Data repository interfaces they
 * wrap are not and cannot be: scanning is what finds them, and the application class turned scanning
 * on unconditionally. So the context asked for an {@code entityManagerFactory} that the profile had
 * deliberately removed, and failed. Excluding the JPA auto-configurations does not help — excluding
 * an auto-configuration does not stop repository scanning.
 *
 * <p>This test is the whole of the guard, and it is a context test on purpose: nothing smaller can
 * fail here. Every unit in the worker is fine; what was broken was the assembly.
 */
@SpringBootTest
@ActiveProfiles("memory")
class MemoryProfileTest {

    @Autowired
    ApplicationContext context;

    @Test
    void the_context_starts_with_no_database_at_all() {
        assertThat(context.getBeanNamesForType(jakarta.persistence.EntityManagerFactory.class))
                .as("no entity manager: the profile's whole point is that there is no database")
                .isEmpty();
    }

    @Test
    void the_stores_are_the_in_memory_ones() {
        assertThat(context.getBean(ReceivedTaskStore.class).getClass().getSimpleName())
                .isEqualTo("InMemoryReceivedTaskStore");
        assertThat(context.getBean(TaskOverrideStore.class).getClass().getSimpleName())
                .isEqualTo("InMemoryTaskOverrideStore");
    }
}
