package io.mateu.workflow.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

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
 *
 * <p><b>The data source is resolved here, not required to register.</b> The engines used to guard
 * this bean with {@code @ConditionalOnSingleCandidate(DataSource.class)}, which asks whether the
 * data source's <em>bean definition</em> is already visible at the moment the condition runs. In the
 * standalone applications it was not — the condition reported "did not find any beans" while
 * Hikari's own autoconfiguration matched in the very same report — so the bean was never registered
 * and the engine silently applied no migrations at all. Resolving the data source when this bean is
 * <em>created</em> instead of when it is <em>defined</em> takes that ordering out of the picture.
 */
public class ManagedSchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ManagedSchemaInitializer.class);

    private final ManagedSchema schema;
    private final DataSource dataSource;
    private final ObjectProvider<DataSource> dataSources;

    public ManagedSchemaInitializer(ManagedSchema schema, DataSource dataSource) {
        this.schema = schema;
        this.dataSource = dataSource;
        this.dataSources = null;
    }

    public ManagedSchemaInitializer(ManagedSchema schema, ObjectProvider<DataSource> dataSources) {
        this.schema = schema;
        this.dataSource = null;
        this.dataSources = dataSources;
    }

    @Override
    public void afterPropertiesSet() {
        var resolved = resolveDataSource();
        if (resolved != null) {
            schema.migrate(resolved);
        }
    }

    /**
     * The single data source, or {@code null} when there is nothing to migrate against. Keeps the
     * old condition's semantics: exactly one candidate (or one marked primary) is managed, and an
     * ambiguous set is left alone — but says so out loud, because the alternative is an engine that
     * quietly runs on an unmigrated schema.
     */
    private DataSource resolveDataSource() {
        if (dataSource != null) {
            return dataSource;
        }
        var unique = dataSources.getIfUnique();
        if (unique != null) {
            return unique;
        }
        if (dataSources.stream().findAny().isPresent()) {
            log.warn("EventConductor {} schema NOT applied: the application defines more than one "
                            + "DataSource and none is marked @Primary, so the engine cannot tell which "
                            + "one its tables belong to. Mark one @Primary, or set the engine's "
                            + "schema.enabled=false and apply {} yourself.",
                    schema.engine(), schema.location());
        }
        return null;
    }
}
