package io.mateu.workflow.contentservice.application.usecases.label.update;

import io.mateu.workflow.contentservice.application.out.LabelRepository;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateLabelUseCase {

final LabelRepository repository;

@Transactional
public void handle(UpdateLabelCommand command) {
var label = repository.findById(new LabelId(Long.valueOf(command.id()))).orElseThrow();
label.update(new LabelName(command.name()));
repository.save(label);
}

}
