package io.mateu.workflow.controlplaneservice.application.usecases.environment.update;

import io.mateu.workflow.controlplaneservice.application.out.EnvironmentRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateEnvironmentUseCase {

final EnvironmentRepository repository;

@Transactional
public void handle(UpdateEnvironmentCommand command) {
var environment = repository.findById(new EnvironmentId(command.id())).orElseThrow();
environment.update(new EnvironmentName(command.name()));
repository.save(environment);
}

}
