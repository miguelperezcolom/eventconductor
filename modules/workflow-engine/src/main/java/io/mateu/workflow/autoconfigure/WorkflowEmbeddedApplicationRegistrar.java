package io.mateu.workflow.autoconfigure;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Custom registrar for {@link WorkflowEmbeddedApplication}.
 * <p>
 * Ensures that:
 * 1. The user's own application base package is automatically scanned for Spring components (Stereotype components, beans, etc.).
 * 2. The user's base package and EventConductor's internal persistence package are both registered as auto-configuration packages
 *    so that Hibernate and Spring Data JPA can resolve entity and repository mappings seamlessly without manual intervention.
 */
public class WorkflowEmbeddedApplicationRegistrar implements
        ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware, BeanFactoryAware {

    private Environment environment;
    private ResourceLoader resourceLoader;
    private BeanFactory beanFactory;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        String className = importingClassMetadata.getClassName();
        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) {
            String packageName = className.substring(0, lastDot);

            // 1. Register base package and persistence package for AutoConfiguration Packages.
            // This enables Hibernate entity scan and Spring Data JPA repository scan to find both custom
            // user entities/repositories and internal EventConductor components.
            if (!packageName.startsWith("io.mateu.workflow")) {
                if (!AutoConfigurationPackages.has(this.beanFactory) || !AutoConfigurationPackages.get(this.beanFactory).contains(packageName)) {
                    AutoConfigurationPackages.register(registry, packageName);
                }
            }
            if (!AutoConfigurationPackages.has(this.beanFactory) || !AutoConfigurationPackages.get(this.beanFactory).contains("io.mateu.workflow.infra.out.persistence")) {
                AutoConfigurationPackages.register(registry, "io.mateu.workflow.infra.out.persistence");
            }

            // 2. Scan the user's base package for stereotype components (@Component, @Service, @Repository, @Controller).
            // Prevents custom user components from being silently ignored.
            if (!packageName.startsWith("io.mateu.workflow")) {
                ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(registry, true);
                if (this.environment != null) {
                    scanner.setEnvironment(this.environment);
                }
                if (this.resourceLoader != null) {
                    scanner.setResourceLoader(this.resourceLoader);
                }
                scanner.scan(packageName);
            }
        }
    }
}
