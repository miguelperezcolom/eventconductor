package io.mateu.workflow.controlplaneservice.application.usecases.site.create;

import io.mateu.workflow.controlplaneservice.application.out.SiteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.Site;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateSiteUseCase {

final SiteRepository repository;

@Transactional
public String handle(CreateSiteCommand command) {
return repository.save(Site.of(new SiteName(command.name()))
).id().toString();
}

}
