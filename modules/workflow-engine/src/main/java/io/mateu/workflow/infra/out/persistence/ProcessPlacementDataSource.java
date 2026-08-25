package io.mateu.workflow.infra.out.persistence;

/**
 * The engine's connection to the database holding the <b>placement claims</b>. Writable, unlike
 * {@link ProcessIndexDataSource}: claiming a placement is an insert, and it is on the creation path.
 * See {@link NonPrimaryDataSource} for why this is not a {@code DataSource} bean.
 *
 * <p>Usually the same database as the read model — they are deployed together — but a separate pool,
 * because one of them must be read-only and the other must not.
 */
public class ProcessPlacementDataSource extends NonPrimaryDataSource {

    public ProcessPlacementDataSource(String url, String username, String password, int poolSize) {
        super("process-placement", url, username, password, poolSize, false);
    }
}
