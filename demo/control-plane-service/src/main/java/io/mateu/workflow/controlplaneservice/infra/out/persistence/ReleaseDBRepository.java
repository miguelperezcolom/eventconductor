package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.ReleaseRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.environment.vo.EnvironmentId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.Release;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseDate;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.UserId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class ReleaseDBRepository implements ReleaseRepository {

    final ReleaseEntityRepository repository;

    @Override
    public Optional<Release> findById(ReleaseId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private Release toDomain(ReleaseEntity entity) {
        return new Release(
                new ReleaseId(entity.id),
                new ReleaseName(entity.name),
                new UserId(entity.userId),
                new ReleaseDate(entity.date),
                new EnvironmentId(entity.environmentId),
                new SiteId(entity.siteId),
                listFromJson(entity.pageIdsJson, Long.class).stream().map(PageId::new).toList(),
                listFromJson(entity.countryCodesJson, String.class).stream().map(CountryCode::new).toList(),
                listFromJson(entity.languageCodesJson, String.class).stream().map(LanguageCode::new).toList()
        );
    }

    private ReleaseEntity toEntity(Release release) {
        return new ReleaseEntity(
                release.getId() != null ? Long.valueOf(release.getId().id()) : null,
                release.getName().name(),
                release.getUser().name(),
                release.getDate().dateTime(),
                toJson(release.getLanguages().stream().map(LanguageCode::code).toList()),
                toJson(release.getPages().stream().map(PageId::id).toList()),
                toJson(release.getCountries().stream().map(CountryCode::code).toList()),
                release.getEnvironment().id(), release.getSite().id()
        );
    }

    @Override
    public ReleaseId save(Release release) {
        return new ReleaseId(repository.save(toEntity(release)).id);
    }

    @Override
    public void deleteAllById(List<ReleaseId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(ReleaseId::id).toList());
    }
}
