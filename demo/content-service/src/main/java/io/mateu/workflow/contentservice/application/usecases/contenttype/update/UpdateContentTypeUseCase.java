package io.mateu.workflow.contentservice.application.usecases.contenttype.update;

import io.mateu.workflow.contentservice.application.out.ContentTypeRepository;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateContentTypeUseCase {

final ContentTypeRepository repository;

@Transactional
public void handle(UpdateContentTypeCommand command) {
var contenttype = repository.findById(new ContentTypeId(Long.valueOf(command.id()))).orElseThrow();
contenttype.update(new ContentTypeName(command.name()));
repository.save(contenttype);
}

}
