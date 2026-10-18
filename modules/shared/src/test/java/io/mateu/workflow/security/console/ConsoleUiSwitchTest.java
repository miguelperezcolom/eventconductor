package io.mateu.workflow.security.console;

import io.mateu.workflow.infra.in.ui.FakeConsoleController;
import io.mateu.workflow.security.console.app.AppApiController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleUiSwitchTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConsoleAccessAutoConfiguration.class))
            .withBean(FakeConsoleController.class)
            .withBean(AppApiController.class);

    @Test
    void theUiIsMountedByDefault() {
        web.run(context -> {
            assertThat(context).hasSingleBean(FakeConsoleController.class).hasSingleBean(AppApiController.class);
            assertThat(context).hasSingleBean(ConsoleAccess.class).hasBean("eventConductorConsoleAccessConfigurer");
            assertThat(context.getBean(ConsoleAccess.class).allows()).as("no chain, no allow-anonymous").isFalse();
        });
        web.withPropertyValues("workflow.ui.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(FakeConsoleController.class));
    }

    @Test
    void uiEnabledFalseDropsEveryConsoleBeanAndNothingElse() {
        web.withPropertyValues("workflow.ui.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(FakeConsoleController.class)
                    .hasSingleBean(AppApiController.class).hasSingleBean(ConsoleAccess.class);
        });
    }

    @Test
    void allowAnonymousIsReadFromTheEnvironment() {
        web.withPropertyValues("workflow.message-api.allow-anonymous=true")
                .run(context -> assertThat(context.getBean(ConsoleAccess.class).allows()).isTrue());
    }

    @Test
    void notAWebApplicationNotConfigured() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConsoleAccessAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(ConsoleAccess.class)
                        .doesNotHaveBean(WebMvcConfigurer.class));
    }

    @Test
    void withoutAnEnvironmentItLeavesTheRegistryAlone() {
        var registry = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        registry.registerBeanDefinition("console",
                new org.springframework.beans.factory.support.RootBeanDefinition(FakeConsoleController.class));
        var uiSwitch = new ConsoleUiSwitch();
        uiSwitch.postProcessBeanDefinitionRegistry(registry);
        uiSwitch.postProcessBeanFactory(registry);
        assertThat(registry.containsBeanDefinition("console")).isTrue();
        assertThat(uiSwitch.getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }
}
