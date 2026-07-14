package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.application.services.RuleEvaluator;
import io.mateu.workflow.application.usecases.evaluaterule.EvaluateRuleUseCase;
import io.mateu.workflow.infra.out.cache.CachingRuleSource;
import io.mateu.workflow.infra.out.classpath.ClasspathRuleSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RuleRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuleRuntimeAutoConfiguration.class));

    @Test
    void defaultSourceIsLocalSoNoSourceBeanIsCreatedHere() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RuleSource.class);
            assertThat(context).hasSingleBean(RuleEvaluator.class);
            assertThat(context).hasSingleBean(EvaluateRuleUseCase.class);
        });
    }

    @Test
    void classpathSourceIsCreatedWhenSelected() {
        runner.withPropertyValues("rules.source=classpath")
                .run(context -> assertThat(context).getBean(RuleSource.class)
                        .isInstanceOf(ClasspathRuleSource.class));
    }

    @Test
    void restSourceIsWrappedInCache() {
        runner.withPropertyValues("rules.source=rest", "rules.catalog.url=http://localhost:9999")
                .run(context -> assertThat(context).getBean(RuleSource.class)
                        .isInstanceOf(CachingRuleSource.class));
    }

    @Test
    void grpcSourceIsWrappedInCache() {
        runner.withPropertyValues("rules.source=grpc", "rules.catalog.grpc-target=localhost:9090")
                .run(context -> assertThat(context).getBean(RuleSource.class)
                        .isInstanceOf(CachingRuleSource.class));
    }

    @Test
    void userProvidedSourceWins() {
        runner.withPropertyValues("rules.source=classpath")
                .withBean("mySource", RuleSource.class, () -> new CachingRuleSource(null, null))
                .run(context -> assertThat(context).getBean(RuleSource.class)
                        .isInstanceOf(CachingRuleSource.class));
    }
}
