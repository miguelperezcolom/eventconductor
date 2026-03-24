package io.mateu.workflow.controlplaneservice.application.usecases.site.update;

import io.mateu.workflow.controlplaneservice.application.out.SiteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateSiteUseCase {

final SiteRepository repository;

@Transactional
public void handle(UpdateSiteCommand command) {
var site = repository.findById(new SiteId(Long.valueOf(command.id()))).orElseThrow();
site.update(new SiteName(command.name()));
repository.save(site);
}

}
