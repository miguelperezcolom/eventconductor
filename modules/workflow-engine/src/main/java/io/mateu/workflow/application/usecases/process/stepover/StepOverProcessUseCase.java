package io.mateu.workflow.application.usecases.process.stepover;

import io.mateu.workflow.infra.in.ui.adapters.ProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StepOverProcessUseCase {

    final ProcessCrudAdapter processCrudAdapter;

    public void handle(StepOverProcessCommand command) {
        // leer proceso
        // actualizar variables
        // comprobar siguiente paso
        // grabar y lanzar eventos
    }

}
