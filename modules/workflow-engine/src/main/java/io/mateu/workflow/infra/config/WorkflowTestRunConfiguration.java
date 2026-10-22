package io.mateu.workflow.infra.config;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.LockService;
import io.mateu.workflow.application.out.TestRunMutex;
import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessSnapshotReader;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.WorkflowTestRunService;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkflowTestRunConfiguration {
    @Bean
    TestRunMutex testRunMutex(LockService namedLocks, ProcessLockService processes) {
        return (runId, action) -> {
            // A process row lock cannot exclude two creators of a row that does not exist yet.
            // Unique owners also prevent two submissions being treated as a reentrant acquisition.
            String owner = java.util.UUID.randomUUID().toString();
            String domain = "eventconductor.ide-test-submission";
            if (namedLocks.tryAcquire(domain, runId, owner) != LockService.Outcome.ACQUIRED)
                throw new ConcurrentProcessAccessException(runId, null);
            try {
                ProcessLockService.runExclusivelyOrThrow(processes, runId, action);
            } finally {
                namedLocks.release(domain, runId, owner);
            }
        };
    }

    @Bean
    WorkflowTestRunService workflowTestRunService(ProcessRepository processes, WorkflowDefinitionRepository definitions,
                                                CreateProcessUseCase creator, TestRunMutex locks,
                                                ProcessSnapshotReader snapshots,
                                                @Value("${workflow.security.flow-authorization.enabled:false}") boolean authorization,
                                                @Value("${workflow.test-runs.required-roles:${workflow.security.operations.required-roles:}}")
                                                java.util.List<String> requiredRoles) {
        return new WorkflowTestRunService(processes, definitions, creator, locks, snapshots, authorization, requiredRoles);
    }
}
