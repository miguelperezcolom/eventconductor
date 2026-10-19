package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.infra.config.BatchOperationProperties;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** A {@link BatchOperationJob}'s tick, for tests in other packages. */
public final class BatchOperationJobAccess {

    private final BatchOperationJob job;

    private BatchOperationJobAccess(BatchOperationJob job) {
        this.job = job;
    }

    public static BatchOperationJobAccess of(BatchOperationService service, BatchOperationProperties properties,
                                             ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<DbLockDialect> dialect) {
        return new BatchOperationJobAccess(new BatchOperationJob(service, properties, jdbc, dialect));
    }

    public boolean runOnce() {
        return job.runOnce() > 0;
    }
}
