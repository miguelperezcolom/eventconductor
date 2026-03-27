package io.mateu.workflow.contentservice.application.usecases.content.create;

import io.mateu.workflow.contentservice.application.out.ContentRepository;
import io.mateu.workflow.contentservice.domain.aggregates.content.Content;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentId;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentName;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.ContentValue;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateContentUseCase {

final ContentRepository repository;

@Transactional
public String handle(CreateContentCommand command) {
return repository.save(Content.of(
        new ContentName(command.name()),
        new ContentTypeId(Long.valueOf(command.contentType())),
        command.labels().stream().map(Long::valueOf).map(LabelId::new).toList(),
        command.values().stream()
        .map(value -> new ContentValue(value.language(), value.country(), value.value()))
        .toList())
).id().toString();
}

}
