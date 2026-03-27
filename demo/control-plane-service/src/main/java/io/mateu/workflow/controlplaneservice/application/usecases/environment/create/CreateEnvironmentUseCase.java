package io.mateu.workflow.controlplaneservice.application.usecases.environment.create;

import io.mateu.workflow.controlplaneservice.application.out.EnvironmentRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.Environment;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateEnvironmentUseCase {

    final EnvironmentRepository repository;

    @Transactional
    public String handle(CreateEnvironmentCommand command) {
        return repository.save(Environment.of(new EnvironmentId(command.id()), new EnvironmentName(command.name()))
        ).id();
    }

}
