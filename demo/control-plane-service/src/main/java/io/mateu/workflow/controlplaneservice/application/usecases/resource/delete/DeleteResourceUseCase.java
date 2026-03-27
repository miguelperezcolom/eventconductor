package io.mateu.workflow.controlplaneservice.application.usecases.resource.delete;

import io.mateu.workflow.controlplaneservice.application.out.ResourceRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.resource.vo.ResourceId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteResourceUseCase {

    final ResourceRepository repository;

    @Transactional
    public void handle(DeleteResourceCommand command) {
        repository.deleteAllById(command.ids().stream()
                .map(ResourceId::new)
                .toList());
    }

}
