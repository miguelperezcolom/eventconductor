package io.mateu.workflow.application.usecases.scheduler.triggertimeoutchecks;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.infra.in.ui.adapters.ProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TriggerTimeoutChecksUseCase {

    final ProcessRepository processRepository;

    public void handle(TriggerTimeoutChecksCommand command) {
        // leer pasos en ejecución con timeouts
        // lanzar eventos (si no han sido lanzados para un momento posterior?)
    }

}
