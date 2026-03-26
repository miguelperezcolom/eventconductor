package io.mateu.workflow.controlplaneservice.application.usecases.site.update;

import io.mateu.workflow.controlplaneservice.application.out.SiteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteUrl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateSiteUseCase {

final SiteRepository repository;

@Transactional
public void handle(UpdateSiteCommand command) {
var site = repository.findById(new SiteId(command.id())).orElseThrow();
site.update(new SiteName(command.name()), new SiteUrl(command.url()));
repository.save(site);
}

}
