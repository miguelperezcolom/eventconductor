package io.mateu.workflow.schema;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * The database schema of one EventConductor engine, owned and applied by that engine.
 *
 * <p>The migrations ship inside the engine's own jar, and the engine runs them itself at startup.
 * They used to live in the standalone applications instead, which meant the schema only existed for
 * the deployment shape we happened to ship: anyone <em>embedding</em> the engine got no migrations
 * at all and ran on whatever {@code ddl-auto} produced — primary keys and nothing else, because
 * Hibernate's {@code update} path emits no index DDL. Every deadline scan, outbox claim and message
 * correlation then becomes a sequential scan. The engine's schema is part of the engine, so it
 * travels with it.
 *
 * <p><b>Its own history table.</b> Flyway identifies a migration by version number, and the host
 * application has its own migrations numbered from V1 as well. Sharing one history table would
 * collide the two numbering spaces on the first migration either side adds. Each engine therefore
 * records its history in a table of its own and never touches the host's, so the engine's schema
 * and the application's evolve independently in the same database.
 *
 * <p><b>Baselining at 0, not at 1.</b> {@code baselineOnMigrate} treats a non-empty schema as
 * "already up to date" and skips the baseline version — a fair guess for one application per
 * database, and wrong for both shapes we actually run: an embedder whose tables are the host's,
 * and the chart's deployment where the orchestrator, forms and rules apps share one database.
 * Whichever engine starts second finds tables (somebody else's), skips its own V1 and then dies on
 * the first migration that touches a table it never created. Every V1 creates only
 * {@code IF NOT EXISTS}, so applying it to a schema that already has those tables does nothing —
 * which is also what makes adopting this over a schema {@code ddl-auto} already built safe.
 */
public final class ManagedSchema {

    private static final Logger log = LoggerFactory.getLogger(ManagedSchema.class);

    private final String engine;
    private final String location;
    private final String historyTable;

    /**
     * @param engine       the engine whose schema this is ({@code workflow}, {@code forms},
     *                     {@code rules}) — used only in log lines
     * @param location     the Flyway location inside the engine jar, e.g.
     *                     {@code classpath:db/migration/workflow}
     * @param historyTable the table this engine records its own migration history in
     */
    public ManagedSchema(String engine, String location, String historyTable) {
        this.engine = engine;
        this.location = location;
        this.historyTable = historyTable;
    }

    /** The engine this schema belongs to ({@code workflow}, {@code forms}, {@code rules}). */
    public String engine() {
        return engine;
    }

    /** The Flyway location these migrations are read from. */
    public String location() {
        return location;
    }

    /**
     * Brings the engine's schema up to date on the given data source. Runs before the
     * {@code EntityManagerFactory} is built, so Hibernate always sees the finished schema.
     */
    public void migrate(DataSource dataSource) {
        // The engine's own classloader rather than the thread context one: the migrations are
        // resources of the engine jar, and this has to resolve the same way whether the engine is
        // on a plain classpath, inside a Boot fat jar, or in a host application's module layer.
        var flyway = Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();

        var result = flyway.migrate();

        if (result.migrationsExecuted == 0) {
            log.info("EventConductor {} schema is up to date (history in {}).", engine, historyTable);
        } else {
            log.info("EventConductor {} schema migrated to {} ({} migration(s) applied, history in {}).",
                    engine, result.targetSchemaVersion, result.migrationsExecuted, historyTable);
        }
    }
}
