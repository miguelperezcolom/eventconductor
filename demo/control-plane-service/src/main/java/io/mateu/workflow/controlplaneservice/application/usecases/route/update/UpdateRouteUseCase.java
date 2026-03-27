package io.mateu.workflow.controlplaneservice.application.usecases.route.update;

import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.country.vo.CountryCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageCode;
import io.mateu.workflow.controlplaneservice.domain.aggregates.page.vo.PageId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteName;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RoutePath;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteUrl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UpdateRouteUseCase {

final RouteRepository repository;

@Transactional
public void handle(UpdateRouteCommand command) {
var route = repository.findById(new RouteId(Long.valueOf(command.id()))).orElseThrow();
route.update(new RouteName(command.name()),
        new LanguageCode(command.languageCode()),
        new CountryCode(command.countryCode()),
        new PageId(command.pageId()),
        new RoutePath(command.path()),
        new RouteUrl(command.url()));
repository.save(route);
}

}
