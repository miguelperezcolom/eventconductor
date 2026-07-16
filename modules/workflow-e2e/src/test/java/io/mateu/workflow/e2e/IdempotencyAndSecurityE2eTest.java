package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** E2E-IDEM-01, E2E-SEC-01. */
class IdempotencyAndSecurityE2eTest extends AbstractE2eTest {

    @Test
    void duplicateCreationWithSameBusinessKeyProducesOneProcess() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        var event = new ProcessCreationRequested("sequential-3", "dup-key", List.of());
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event));
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event)); // redelivery

        long count = processRepository.findAll().stream()
                .filter(p -> "dup-key".equals(p.getBusinessKey()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void jexlPreconditionCannotExecuteArbitraryCode() {
        // The malicious precondition tries to reach java.lang.Runtime via reflection.
        // With the RESTRICTED sandbox this throws → fail closed → the exploit step never runs.
        worker.on("gate", TestWorker.succeed());
        worker.on("exploit", TestWorker.succeed());

        createProcess("jexl-sandbox", "sec-1");

        assertThat(worker.invocationsOf("exploit"))
                .as("sandboxed JEXL must not allow reflection; guard fails closed")
                .isEqualTo(0);
        assertThat(process("sec-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }
}
