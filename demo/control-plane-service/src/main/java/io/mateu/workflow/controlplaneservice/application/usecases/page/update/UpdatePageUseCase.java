package io.mateu.workflow.controlplaneservice.application.usecases.page.update;

import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdatePageUseCase {

final PageRepository repository;

@Transactional
public void handle(UpdatePageCommand command) {
var page = repository.findById(new PageId(Long.valueOf(command.id()))).orElseThrow();
page.update(new PageName(command.name()));
repository.save(page);
}

}
