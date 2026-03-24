package io.mateu.workflow.contentservice.application.usecases.content.update;

import io.mateu.workflow.contentservice.application.out.ContentRepository;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentId;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateContentUseCase {

final ContentRepository repository;

@Transactional
public void handle(UpdateContentCommand command) {
var content = repository.findById(new ContentId(Long.valueOf(command.id()))).orElseThrow();
content.update(new ContentName(command.name()));
repository.save(content);
}

}
