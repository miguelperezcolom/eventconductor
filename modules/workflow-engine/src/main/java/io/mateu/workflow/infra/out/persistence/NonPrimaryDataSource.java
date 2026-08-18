package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * A connection to a database that is <b>not</b> the engine's own, held in a wrapper that is
 * deliberately not a {@link DataSource}.
 *
 * <p>That is the whole point of the class, and it is not stylistic. Registering a second
 * {@code DataSource} bean would break two things at once: Boot's data-source auto-configuration backs
 * off as soon as one is declared, and {@code ManagedSchemaInitializer} resolves the engine's schema
 * through {@code ObjectProvider#getIfUnique} and gives up — with a warning, not a failure — the moment
 * there is more than one candidate and none is primary. An engine that quietly stops migrating its own
 * write schema because someone enabled a read model is exactly the silent downgrade the schema
 * autoconfiguration exists to prevent.
 *
 * <p>So the extra databases travel as plain objects nobody else is looking for, and each has a type of
 * its own so that what wants one asks for that one. The two subclasses are separate pools on purpose
 * as well as by type: the read model is opened read-only (in remote mode the standalone projector is
 * its only writer), and the placement store has to be writable.
 */
public abstract class NonPrimaryDataSource implements AutoCloseable {

    private final HikariDataSource dataSource;

    protected NonPrimaryDataSource(String poolName, String url, String username, String password,
                                   int poolSize, boolean readOnly) {
        var hikari = new HikariDataSource();
        hikari.setPoolName(poolName);
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(poolSize);
        hikari.setReadOnly(readOnly);
        this.dataSource = hikari;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
