package io.mateu.workflow.application.usecases.stepexecution.complete;

import io.mateu.workflow.application.out.ProcessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompleteStepExecutionUseCase {

    final ProcessRepository processRepository;

    public void handle(CompleteStepExecutionCommand command) {
        // leer ejecución paso
        // actualizar variables
        // comprobar siguiente paso
        // grabar y lanzar eventos
    }

}
