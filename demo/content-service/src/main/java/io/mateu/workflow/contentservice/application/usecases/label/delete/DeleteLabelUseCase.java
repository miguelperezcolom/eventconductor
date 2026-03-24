package io.mateu.workflow.contentservice.application.usecases.label.delete;

import io.mateu.workflow.contentservice.application.out.LabelRepository;
import io.mateu.workflow.contentservice.domain.aggregates.label.vo.LabelId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteLabelUseCase {

final LabelRepository repository;

@Transactional
public void handle(DeleteLabelCommand command) {
repository.deleteAllById(command.ids().stream()
.map(Long::valueOf)
.map(LabelId::new)
.toList());
}

}
