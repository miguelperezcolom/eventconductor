package io.mateu.workflow.controlplaneservice.application.usecases.environment.delete;

import io.mateu.workflow.controlplaneservice.application.out.EnvironmentRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteEnvironmentUseCase {

    final EnvironmentRepository repository;

    @Transactional
    public void handle(DeleteEnvironmentCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(EnvironmentId::new)
                .toList());
    }

}
