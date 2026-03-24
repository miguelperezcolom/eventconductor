package io.mateu.workflow.controlplaneservice.application.usecases.route.create;

import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.Route;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateRouteUseCase {

final RouteRepository repository;

@Transactional
public String handle(CreateRouteCommand command) {
return repository.save(Route.of(new RouteName(command.name()))
).id().toString();
}

}
