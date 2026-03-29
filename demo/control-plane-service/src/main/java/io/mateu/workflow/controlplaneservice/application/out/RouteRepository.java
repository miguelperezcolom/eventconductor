package io.mateu.workflow.controlplaneservice.application.out;

import io.mateu.workflow.controlplaneservice.domain.aggregates.route.Route;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;

import java.util.Collection;
import java.util.List;

public interface RouteRepository extends Repository<Route, RouteId> {
    List<Route> findAll();
}
