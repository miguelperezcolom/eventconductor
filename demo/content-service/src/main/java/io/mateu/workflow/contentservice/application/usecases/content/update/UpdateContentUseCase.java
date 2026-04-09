package io.mateu.workflow.contentservice.application.usecases.content.update;

import io.mateu.workflow.contentservice.application.out.ContentRepository;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.*;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;
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
content.update(new ContentName(command.name()),
        new ContentTypeId(Long.valueOf(command.contentType())),
        command.labels().stream().map(Long::valueOf).map(LabelId::new).toList(), command.values().stream()
        .map(value -> new ContentValue(value.language(), value.country(), value.value()))
        .toList());
repository.save(content);
}

}
