package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.SiteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.Site;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.site.vo.SiteName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static io.mateu.core.infra.JsonSerializer.listFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

@Service
@RequiredArgsConstructor
public class SiteDBRepository implements SiteRepository {

final SiteEntityRepository repository;

@Override
public Optional<Site> findById(SiteId id) {
    return repository.findById(id.id()).map(this::toDomain);
    }

    private Site toDomain(SiteEntity entity) {
    return new Site(
    new SiteId(entity.id),
    new SiteName(entity.name)
    );
    }

    private SiteEntity toEntity(Site site) {
    return new SiteEntity(
site.getId() != null?Long.valueOf(site.getId().id()):null,
site.getName().name()
    );
    }

    @Override
    public SiteId save(Site site) {
    return new SiteId(repository.save(toEntity(site)).id);
    }

    @Override
    public void deleteAllById(List<SiteId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(SiteId::id).toList());
        }
        }
