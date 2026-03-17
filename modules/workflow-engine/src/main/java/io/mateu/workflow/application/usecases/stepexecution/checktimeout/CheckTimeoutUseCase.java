package io.mateu.workflow.application.usecases.stepexecution.checktimeout;

import io.mateu.workflow.infra.in.ui.adapters.ProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CheckTimeoutUseCase {

    final ProcessCrudAdapter processCrudAdapter;

    public void handle(CheckTimeoutCommand command) {
        // leer ejecución paso
        // si timeout y todavía no está... marcar como fallo
        // grabar y lanzar eventos
    }

}
