package io.mateu.workflow.controlplaneservice.application.usecases.route.delete;

import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeleteRouteUseCase {

final RouteRepository repository;

@Transactional
public void handle(DeleteRouteCommand command) {
repository.deleteAllById(command.ids().stream()
.map(Long::valueOf)
.map(RouteId::new)
.toList());
}

}
