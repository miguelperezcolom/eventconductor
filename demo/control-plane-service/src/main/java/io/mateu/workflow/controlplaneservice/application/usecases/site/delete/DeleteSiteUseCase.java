package io.mateu.workflow.controlplaneservice.application.usecases.site.delete;

import io.mateu.workflow.controlplaneservice.application.out.SiteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteSiteUseCase {

final SiteRepository repository;

@Transactional
public void handle(DeleteSiteCommand command) {
repository.deleteAllById(command.ids().stream()
.map(Long::valueOf)
.map(SiteId::new)
.toList());
}

}
