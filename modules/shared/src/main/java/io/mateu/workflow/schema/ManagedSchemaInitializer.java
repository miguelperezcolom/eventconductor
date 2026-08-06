package io.mateu.workflow.schema;

import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;

/**
 * Applies a {@link ManagedSchema} as a bean, so the migration happens during context refresh rather
 * than at the first query.
 *
 * <p>It exists as a bean, and not as a call somewhere in a factory method, so that the
 * {@code EntityManagerFactory} can be made to depend on it: Hibernate must not see the schema
 * half-migrated, and without an explicit dependency the two are ordered by whatever the bean
 * definitions happen to imply. Each engine's autoconfiguration registers an
 * {@code EntityManagerFactoryDependsOnPostProcessor} pointing at this type.
 */
public class ManagedSchemaInitializer implements InitializingBean {

    private final ManagedSchema schema;
    private final DataSource dataSource;

    public ManagedSchemaInitializer(ManagedSchema schema, DataSource dataSource) {
        this.schema = schema;
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        schema.migrate(dataSource);
    }
}
