package io.mateu.workflow.application.usecases.eventloop;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdvanceUseCase {

    final ProcessRepository processRepository;
    final StepOverProcessUseCase stepOverProcessUseCase;

    public void handle(AdvanceCommand command) {
        processRepository.findAll().stream()
                .filter(process -> ProcessStatus.PENDING.equals(process.getStatus()) ||
                        ProcessStatus.RUNNING.equals(process.getStatus()))
                .forEach(process -> stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId())));
    }

}
