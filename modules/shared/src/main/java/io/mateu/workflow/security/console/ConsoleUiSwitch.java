package io.mateu.workflow.security.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * {@code workflow.ui.enabled=false}: the admin UI is not mounted at all. Every bean the EventConductor
 * modules contribute from {@code io.mateu.workflow.infra.in.ui} — the pages, their adapters, the
 * controllers Mateu's annotation processor generates for {@code /_workflow}, {@code /_workflow-admin},
 * {@code /_rules}, {@code /_forms…}, and the task-project zips — is dropped before any is created,
 * so those routes answer 404.
 *
 * <p>Done on the registry rather than with a condition on each class because half of those classes
 * are generated: the {@code @UI} controllers cannot carry an annotation of ours.
 */
public class ConsoleUiSwitch implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ConsoleUiSwitch.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (environment == null || environment.getProperty(ConsoleAccess.UI_ENABLED, Boolean.class, true)) {
            return;
        }
        int removed = 0;
        for (var name : registry.getBeanDefinitionNames()) {
            var className = registry.getBeanDefinition(name).getBeanClassName();
            if (className != null && className.startsWith(ConsoleAccessInterceptor.CONSOLE_PACKAGE)) {
                registry.removeBeanDefinition(name);
                removed++;
            }
        }
        log.info("{}=false: the admin UI is not mounted ({} beans of {} dropped).", ConsoleAccess.UI_ENABLED, removed,
                ConsoleAccessInterceptor.CONSOLE_PACKAGE + "*");
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // nothing: the work is done on the registry
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
