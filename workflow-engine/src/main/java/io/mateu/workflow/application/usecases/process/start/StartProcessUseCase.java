package io.mateu.workflow.application.usecases.process.start;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.shared.AggregateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

record Pair<K, V>(K key, V value) {}

@Service
@RequiredArgsConstructor
public class StartProcessUseCase {

    final AggregateRepository<Process, String> processRepository;
    final AggregateRepository<StepExecution, String> stepExecutionRepository;

    public void handle(StartProcessCommand command) {
        // crear y grabar proceso
        var process = processRepository.findById(command.processId()).orElseThrow();
        process.getStepExecutions().stream()
                .map(stepExecutionId -> stepExecutionRepository.findById(stepExecutionId).orElseThrow())
                .map(stepExecution -> new Pair<StepExecution, Step>(stepExecution, pojoFromJson(stepExecution.getStepJson(), Step.class)))
                .filter(pair -> pair.value().precondition() == null || pair.value().precondition().stepId() == null || pair.value().precondition().stepId().isEmpty())
                .map(Pair::key)
                .map(stepExecution -> stepExecution.start(process.getVariables()))
                .forEach(stepExecutionRepository::save);
        processRepository.save(process.withStatus(ProcessStatus.RUNNING));
        // enviar evento proceso creado (para step over)
    }

}
