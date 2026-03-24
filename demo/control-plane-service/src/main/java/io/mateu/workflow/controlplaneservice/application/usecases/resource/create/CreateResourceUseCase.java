package io.mateu.workflow.controlplaneservice.application.usecases.resource.create;

import io.mateu.workflow.controlplaneservice.application.out.ResourceRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.Resource;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateResourceUseCase {

final ResourceRepository repository;

@Transactional
public String handle(CreateResourceCommand command) {
return repository.save(Resource.of(new ResourceName(command.name()))
).id().toString();
}

}
