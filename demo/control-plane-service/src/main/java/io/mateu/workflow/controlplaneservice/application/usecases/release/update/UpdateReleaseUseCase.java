package io.mateu.workflow.controlplaneservice.application.usecases.release.update;

import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseDate;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.UserId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateReleaseUseCase {

    final ReleaseRepository repository;

    @Transactional
    public void handle(UpdateReleaseCommand command) {
        var release = repository.findById(new ReleaseId(Long.valueOf(command.id()))).orElseThrow();
        release.update(new ReleaseName(command.name()),
                new UserId(command.userId()),
                new ReleaseDate(command.date()),
                new SiteId(command.siteId()));
        repository.save(release);
    }

}
