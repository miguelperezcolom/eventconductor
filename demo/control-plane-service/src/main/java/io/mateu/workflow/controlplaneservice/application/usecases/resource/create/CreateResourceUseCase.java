package io.mateu.workflow.controlplaneservice.application.usecases.resource.create;

import io.mateu.workflow.controlplaneservice.application.out.ResourceRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.Resource;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceContent;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourcePath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateResourceUseCase {

final ResourceRepository repository;

@Transactional
public String handle(CreateResourceCommand command) {
return repository.save(Resource.of(
        new ResourceId(UUID.randomUUID().toString()),
        new ResourceName(command.name()),
        new ResourcePath(""),
        new ResourceContent("".getBytes(StandardCharsets.UTF_8))
)).id();
}

}
