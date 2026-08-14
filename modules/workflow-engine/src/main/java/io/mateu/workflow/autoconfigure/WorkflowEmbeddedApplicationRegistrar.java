package io.mateu.workflow.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Custom registrar for {@link WorkflowEmbeddedApplication}.
 * <p>
 * Ensures that the user's base package and EventConductor's internal persistence package are both
 * registered as auto-configuration packages so that Hibernate can resolve entity mappings
 * seamlessly without manual intervention (using Spring Boot's native AutoConfigurationPackages mechanism).
 */
public class WorkflowEmbeddedApplicationRegistrar implements
        ImportBeanDefinitionRegistrar, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEmbeddedApplicationRegistrar.class);

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        String className = importingClassMetadata.getClassName();
        int lastDot = className.lastIndexOf('.');
        String packageName = "";

        if (lastDot > 0) {
            packageName = className.substring(0, lastDot);
        } else {
            // Edge case guard (review comment from @miguelperezcolom):
            // If the user places their main class in the default (root) package, warn them, but don't fail silently.
            log.warn("The main class '{}' is declared in the default (root) package. " +
                     "Declaring Spring Boot applications in the default package is highly discouraged, " +
                     "as it can lead to massive component-scanning overscan and unexpected bean conflicts.", className);
        }

        // 1. Register EventConductor's persistence package for AutoConfiguration Packages.
        // This is load-bearing and must always run so Hibernate can scan internal entities,
        // regardless of where the user's main class lives.
        if (!AutoConfigurationPackages.has(this.beanFactory) || !AutoConfigurationPackages.get(this.beanFactory).contains("io.mateu.workflow.infra.out.persistence")) {
            AutoConfigurationPackages.register(registry, "io.mateu.workflow.infra.out.persistence");
        }

        // 2. If the user is NOT using the default package, and their package is not the engine package itself,
        // register the user's package for AutoConfiguration Packages (enabling entity scanning of their own entities).
        if (!packageName.isEmpty()) {
            boolean isEnginePackageOrSubpackage = packageName.equals("io.mateu.workflow") || packageName.startsWith("io.mateu.workflow.");
            if (!isEnginePackageOrSubpackage) {
                if (!AutoConfigurationPackages.has(this.beanFactory) || !AutoConfigurationPackages.get(this.beanFactory).contains(packageName)) {
                    AutoConfigurationPackages.register(registry, packageName);
                }
            }
        }
    }
}
