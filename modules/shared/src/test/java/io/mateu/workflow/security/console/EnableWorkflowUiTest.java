package io.mateu.workflow.security.console;

import static org.assertj.core.api.Assertions.assertThat;

import io.mateu.workflow.autoconfigure.EnableWorkflowUi;
import io.mateu.workflow.infra.in.ui.FakeConsoleController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * One story for the admin UI: {@code @EnableWorkflowUi} mounts it (a scan of
 * {@code io.mateu.workflow.infra.in.ui}), {@code workflow.ui.enabled=false} removes it however it
 * was mounted, and once mounted it counts as a console for the access rule.
 */
class EnableWorkflowUiTest {

    @Configuration(proxyBeanMethods = false)
    @EnableWorkflowUi
    static class EmbeddedAppWithTheUi {
    }

    @Configuration(proxyBeanMethods = false)
    static class EmbeddedAppWithoutTheUi {
    }

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConsoleAccessAutoConfiguration.class));

    @Test
    void theAnnotationMountsTheConsolesAndTheAccessRuleSeesThem() {
        web.withUserConfiguration(EmbeddedAppWithTheUi.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FakeConsoleController.class);
            assertThat(context.getBean(ConsoleAccess.class).consolesPresent()).isTrue();
        });
    }

    @Test
    void withoutTheAnnotationNothingIsMounted() {
        web.withUserConfiguration(EmbeddedAppWithoutTheUi.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(FakeConsoleController.class);
            assertThat(context.getBean(ConsoleAccess.class).consolesPresent()).isFalse();
        });
    }

    @Test
    void uiEnabledFalseWinsOverTheAnnotation() {
        web.withUserConfiguration(EmbeddedAppWithTheUi.class)
                .withPropertyValues("workflow.ui.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(FakeConsoleController.class);
                    assertThat(context.getBean(ConsoleAccess.class).consolesPresent()).isFalse();
                });
    }
}
