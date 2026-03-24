package io.mateu.workflow.controlplaneservice.application.usecases.page.delete;

import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeletePageUseCase {

final PageRepository repository;

@Transactional
public void handle(DeletePageCommand command) {
repository.deleteAllById(command.ids().stream()
.map(Long::valueOf)
.map(PageId::new)
.toList());
}

}
