package io.mateu.workflow.schema;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * How the initializer gets hold of a data source.
 *
 * <p>It used to be handed one directly, with
 * {@code @ConditionalOnSingleCandidate(DataSource.class)} on the {@code @Bean} deciding whether the
 * bean existed at all. That condition asks whether the data source's <em>bean definition</em> is
 * visible at the moment it runs, and in a real application boot it was not: the initializer was
 * never registered and not one migration was applied, in every one of the three standalone apps.
 * Resolving the data source when the bean is created keeps the same rule — exactly one candidate is
 * managed, an ambiguous set is left alone — without depending on autoconfiguration ordering.
 */
class ManagedSchemaInitializerTest {

    private static final String LOCATION = "classpath:db/migration/managed-schema-test";

    private DataSource database(String name) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
    }

    private ManagedSchema schema() {
        return new ManagedSchema("probe", LOCATION, "probe_history");
    }

    private DefaultListableBeanFactory beanFactoryWith(DataSource... dataSources) {
        var beanFactory = new DefaultListableBeanFactory();
        for (int i = 0; i < dataSources.length; i++) {
            beanFactory.registerSingleton("dataSource" + i, dataSources[i]);
        }
        return beanFactory;
    }

    private ObjectProvider<DataSource> provider(DataSource... dataSources) {
        return beanFactoryWith(dataSources).getBeanProvider(DataSource.class);
    }

    @Test
    void migratesTheOneDataSourceTheApplicationDefines() throws Exception {
        var dataSource = database("initializer-single");

        new ManagedSchemaInitializer(schema(), provider(dataSource)).afterPropertiesSet();

        assertThat(tableExists(dataSource, "managed_schema_probe")).isTrue();
    }

    /**
     * The regression this arrangement exists for: the data source arrives after the initializer was
     * constructed. Under the old condition that ordering decided whether the schema was managed at
     * all; now it only decides when it is read.
     */
    @Test
    void findsADataSourceRegisteredAfterTheInitializerWasConstructed() throws Exception {
        var beanFactory = beanFactoryWith();
        var initializer =
                new ManagedSchemaInitializer(schema(), beanFactory.getBeanProvider(DataSource.class));

        var dataSource = database("initializer-late");
        beanFactory.registerSingleton("dataSource", dataSource);
        initializer.afterPropertiesSet();

        assertThat(tableExists(dataSource, "managed_schema_probe")).isTrue();
    }

    /** No database means nothing to migrate, not a failed context. */
    @Test
    void doesNothingWhenTheApplicationHasNoDataSource() {
        assertThatCode(() -> new ManagedSchemaInitializer(schema(), provider()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    /**
     * Two data sources and no {@code @Primary}: the engine cannot tell which one its tables belong
     * to, so it migrates neither rather than guessing.
     */
    @Test
    void leavesAnAmbiguousSetOfDataSourcesAlone() throws Exception {
        var one = database("initializer-ambiguous-one");
        var other = database("initializer-ambiguous-other");

        new ManagedSchemaInitializer(schema(), provider(one, other)).afterPropertiesSet();

        assertThat(tableExists(one, "managed_schema_probe")).isFalse();
        assertThat(tableExists(other, "managed_schema_probe")).isFalse();
    }

    private boolean tableExists(DataSource dataSource, String name) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "select count(*) from information_schema.tables where upper(table_name) = ?")) {
            statement.setString(1, name.toUpperCase());
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }
}
