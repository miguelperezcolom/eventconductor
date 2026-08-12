package io.mateu.workflow.autoconfigure;

import com.example.customapp.MyCustomApp;
import com.example.customapp.MyCustomApp.MyCustomUserComponent;
import com.example.customapp.MyCustomAppWithExclusion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEmbeddedApplicationTest {

    @Test
    void customAppCorrectlyScansUserPackageAndRegistersAutoConfigurationPackages() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.main.allow-bean-definition-overriding=true")
                .withUserConfiguration(MyCustomApp.class)
                .withBean(io.mateu.workflow.application.out.EmbeddedTaskExecutor.class, () -> request -> {})
                .run(context -> {
                    // 1. Verify user's own custom package bean was scanned (Point 1)
                    assertThat(context).hasSingleBean(MyCustomUserComponent.class);

                    // 2. Verify both user's package and EventConductor's persistence package are registered as AutoConfigurationPackages (Point 2)
                    List<String> registeredPackages = AutoConfigurationPackages.get(context.getBeanFactory());
                    assertThat(registeredPackages)
                            .contains("com.example.customapp")
                            .contains("io.mateu.workflow.infra.out.persistence");
                });
    }

    @Test
    void customAppSupportsAutoConfigurationExclusions() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.main.allow-bean-definition-overriding=true")
                .withUserConfiguration(MyCustomAppWithExclusion.class)
                .withBean(io.mateu.workflow.application.out.EmbeddedTaskExecutor.class, () -> request -> {})
                .run(context -> {
                    // 3. Verify WorkflowTracingAutoConfiguration was excluded via @AliasFor (Point 3)
                    assertThat(context).doesNotHaveBean(WorkflowTracingAutoConfiguration.class);
                });
    }
}
