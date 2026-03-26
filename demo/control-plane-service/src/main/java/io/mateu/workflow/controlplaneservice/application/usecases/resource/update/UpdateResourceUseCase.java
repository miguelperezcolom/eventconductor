package io.mateu.workflow.controlplaneservice.application.usecases.resource.update;

import io.mateu.workflow.controlplaneservice.application.out.ResourceRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateResourceUseCase {

final ResourceRepository repository;

@Transactional
public void handle(UpdateResourceCommand command) {
var resource = repository.findById(new ResourceId(command.id())).orElseThrow();
resource.update(new ResourceName(command.name()));
repository.save(resource);
}

}
