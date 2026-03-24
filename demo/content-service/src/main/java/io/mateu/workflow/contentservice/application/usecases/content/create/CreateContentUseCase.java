package io.mateu.workflow.contentservice.application.usecases.content.create;

import io.mateu.workflow.contentservice.application.out.ContentRepository;
import io.mateu.workflow.contentservice.domain.aggregates.content.Content;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentId;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateContentUseCase {

final ContentRepository repository;

@Transactional
public String handle(CreateContentCommand command) {
return repository.save(Content.of(new ContentName(command.name()))
).id().toString();
}

}
