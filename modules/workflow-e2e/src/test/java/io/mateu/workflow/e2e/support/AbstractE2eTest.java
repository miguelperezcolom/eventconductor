package io.mateu.workflow.e2e.support;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessCommand;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.application.usecases.stepexecution.retry.RetryStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.retry.RetryStepExecutionUseCase;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

/**
 * Base for e2e tests: boots the engine in embedded + memory mode and exposes helpers to
 * drive processes through the public event surface and inspect the resulting state.
 */
@SpringBootTest(classes = E2eTestApplication.class)
@Import(E2eConfig.class)
public abstract class AbstractE2eTest {

    @Autowired protected TestWorker worker;
    @Autowired protected io.mateu.workflow.application.out.LogMessageRepository logMessageRepository;
    @Autowired protected ProcessRepository processRepository;
    @Autowired protected StepExecutionRepository stepExecutionRepository;
    @Autowired protected ProcessUpstreamEventUseCase processUpstreamEventUseCase;
    @Autowired protected RetryStepExecutionUseCase retryStepExecutionUseCase;
    @Autowired protected RetryProcessUseCase retryProcessUseCase;
    @Autowired protected CancelProcessUseCase cancelProcessUseCase;
    @Autowired protected UpdateStepExecutionUseCase updateStepExecutionUseCase;

    @BeforeEach
    void resetWorker() {
        worker.clear();
    }

    /** Creates a process from a definition and returns the created process (looked up by businessKey). */
    protected Process createProcess(String definitionId, String businessKey, Variable... variables) {
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(
                new ProcessCreationRequested(definitionId, businessKey, List.of(variables))));
        return process(businessKey);
    }

    protected Process process(String businessKey) {
        return processRepository.findByBusinessKey(businessKey).orElseThrow();
    }

    protected List<StepExecution> steps(String businessKey) {
        return stepExecutionRepository.findByProcess(process(businessKey));
    }

    protected StepExecution step(String businessKey, String stepId) {
        return steps(businessKey).stream()
                .filter(s -> stepId.equals(s.getStepId()))
                .findFirst().orElseThrow();
    }

    /** What the process recorded as errors — the Errors tab of its detail page. */
    protected List<String> errorsOf(String businessKey) {
        return logMessageRepository.findByProcessId(process(businessKey).id()).stream()
                .filter(m -> io.mateu.workflow.dtos.MessageType.isError(m.getMessageType()))
                .map(io.mateu.workflow.domain.aggregates.LogMessage::getMessage)
                .toList();
    }
}
