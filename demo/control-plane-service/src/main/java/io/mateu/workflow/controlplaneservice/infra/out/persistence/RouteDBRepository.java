package io.mateu.workflow.controlplaneservice.infra.out.persistence;

import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.Route;
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
                new LanguageCode(entity.languageCode),
                new CountryCode(entity.countryCode),
                new PageId(entity.pageId),
                new RoutePath(entity.path),
                new RouteUrl(entity.url)
        );
    }

    private RouteEntity toEntity(Route route) {
        return new RouteEntity(
                route.getId() != null ? route.getId().id() : null,
                route.getName().name(),
                route.getLanguage().code(),
                route.getCountry().code(),
                route.getPage().id(),
                route.getPath().path(),
                route.getUrl().url()
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
}
