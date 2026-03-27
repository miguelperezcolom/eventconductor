package io.mateu.workflow.controlplaneservice.application.usecases.page.create;

import io.mateu.workflow.controlplaneservice.application.out.PageRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.Page;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageJsonLd;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PagePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreatePageUseCase {

    final PageRepository repository;

    @Transactional
    public String handle(CreatePageCommand command) {
        return repository.save(Page.of(
                new SiteId(command.siteId()),
                new PageName(command.name()),
                new PagePath(command.path()),
                new PageJsonLd(command.jsonLd())
                )
        ).id().toString();
    }

}
