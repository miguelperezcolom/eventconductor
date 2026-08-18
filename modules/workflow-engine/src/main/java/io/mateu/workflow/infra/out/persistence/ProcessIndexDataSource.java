package io.mateu.workflow.infra.out.persistence;

/**
 * The engine's connection to the <b>read</b> database, in {@code workflow.projection.mode=remote}.
 * Read-only: there the standalone projector is the index's only writer, and enforcing that here means
 * a stray write fails where it is made rather than producing an index two processes disagree about.
 * See {@link NonPrimaryDataSource} for why this is not a {@code DataSource} bean.
 */
public class ProcessIndexDataSource extends NonPrimaryDataSource {

    public ProcessIndexDataSource(String url, String username, String password, int poolSize) {
        super("process-index-read", url, username, password, poolSize, true);
    }
}
