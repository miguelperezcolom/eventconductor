package io.mateu.workflow.controlplaneservice.application.usecases.route.update;

import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteName;
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
route.update(new RouteName(command.name()));
repository.save(route);
}

}
