package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.release.vo.ReleaseId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.Route;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteHash;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RoutePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteUrl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RouteDBRepository implements RouteRepository {

    final RouteEntityRepository repository;

    @Override
    public Optional<Route> findById(RouteId id) {
        return repository.findById(id.id()).map(this::toDomain);
    }

    private Route toDomain(RouteEntity entity) {
        return new Route(
                new RouteId(entity.id),
                new RouteName(entity.name),
                entity.languageCode != null?new LanguageCode(entity.languageCode):null,
                new CountryCode(entity.countryCode),
                new PageId(entity.pageId),
                new RoutePath(entity.path),
                new RouteUrl(entity.url),
                new RouteHash(entity.hash),
                new RouteHash(entity.deployedHash),
                new ReleaseId(entity.releaseId),
                new ReleaseId(entity.plannedReleaseId)
        );
    }

    private RouteEntity toEntity(Route route) {
        return new RouteEntity(
                route.getId() != null ? route.getId().id() : null,
                route.getName().name(),
                route.getLanguage() != null?route.getLanguage().code():null,
                route.getCountry() != null?route.getCountry().code():null,
                route.getPage().id(),
                route.getPath().path(),
                route.getUrl().url(),
                route.getHash().hash(),
                route.getDeployedHash().hash(),
                route.getRelease() != null ? route.getRelease().id() : null,
                route.getPlannedRelease() != null ? route.getPlannedRelease().id() : null
        );
    }

    @Override
    public RouteId save(Route route) {
        return new RouteId(repository.save(toEntity(route)).id);
    }

    @Override
    public void deleteAllById(List<RouteId> selectedIds) {
        repository.deleteAllById(selectedIds.stream().map(RouteId::id).toList());
    }

    @Override
    public List<Route> findAll() {
        return repository.findAll().stream().map(this::toDomain).toList();
    }
}
