package io.mateu.workflow.autoconfigure;

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
 * registered as auto-configuration packages so that Hibernate and Spring Data JPA can resolve
 * entity and repository mappings seamlessly without manual intervention.
 */
public class WorkflowEmbeddedApplicationRegistrar implements
        ImportBeanDefinitionRegistrar, BeanFactoryAware {

    private BeanFactory beanFactory;

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

            // Determine package relationship: check if user package is already "io.mateu.workflow" or a subpackage of it
            boolean isEnginePackageOrSubpackage = packageName.equals("io.mateu.workflow") || packageName.startsWith("io.mateu.workflow.");

            // 1. Register base package and persistence package for AutoConfiguration Packages.
            // This enables Hibernate entity scan and Spring Data JPA repository scan to find both custom
            // user entities/repositories and internal EventConductor components.
            if (!isEnginePackageOrSubpackage) {
                if (!AutoConfigurationPackages.has(this.beanFactory) || !AutoConfigurationPackages.get(this.beanFactory).contains(packageName)) {
                    AutoConfigurationPackages.register(registry, packageName);
                }
            }
            if (!AutoConfigurationPackages.has(this.beanFactory) || !AutoConfigurationPackages.get(this.beanFactory).contains("io.mateu.workflow.infra.out.persistence")) {
                AutoConfigurationPackages.register(registry, "io.mateu.workflow.infra.out.persistence");
            }
        }
    }
}
