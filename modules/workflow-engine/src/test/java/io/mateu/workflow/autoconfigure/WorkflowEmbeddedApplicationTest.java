package io.mateu.workflow.autoconfigure;

import com.example.customapp.MyCustomApp;
import com.example.customapp.MyCustomApp.MyCustomUserComponent;
import com.example.customappwithexclusion.MyCustomAppWithExclusion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEmbeddedApplicationTest {

    private static final String EXCLUDES_PROP = "spring.autoconfigure.exclude=" +
            "org.springframework.cloud.stream.config.BindingServiceConfiguration," +
            "org.springframework.cloud.stream.function.FunctionConfiguration," +
            "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration";

    @Test
    void customAppCorrectlyScansUserPackage() {
        new ApplicationContextRunner()
                .withPropertyValues(EXCLUDES_PROP)
                .withUserConfiguration(MyCustomApp.class)
                .withBean(io.mateu.workflow.application.out.EmbeddedTaskExecutor.class, () -> request -> {})
                .run(context -> {
                    // 1. Verify user's own custom package bean was scanned (Point 1)
                    assertThat(context).hasSingleBean(MyCustomUserComponent.class);
                });
    }

    @Test
    void customAppSupportsAutoConfigurationExclusions() {
        new ApplicationContextRunner()
                .withPropertyValues(EXCLUDES_PROP)
                .withUserConfiguration(MyCustomAppWithExclusion.class)
                .withBean(io.mateu.workflow.application.out.EmbeddedTaskExecutor.class, () -> request -> {})
                .run(context -> {
                    // 2. Verify WorkflowTracingAutoConfiguration was excluded via @AliasFor (Point 3)
                    assertThat(context).doesNotHaveBean(WorkflowTracingAutoConfiguration.class);
                });
    }
}
