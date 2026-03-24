package io.mateu.workflow.contentservice.application.usecases.contenttype.delete;

import io.mateu.workflow.contentservice.application.out.ContentTypeRepository;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteContentTypeUseCase {

final ContentTypeRepository repository;

@Transactional
public void handle(DeleteContentTypeCommand command) {
repository.deleteAllById(command.ids().stream()
.map(Long::valueOf)
.map(ContentTypeId::new)
.toList());
}

}
