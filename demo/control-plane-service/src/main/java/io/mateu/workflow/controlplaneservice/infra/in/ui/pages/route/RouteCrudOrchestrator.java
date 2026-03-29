package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.route;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.application.query.dto.RouteRow;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteHash;
import io.mateu.workflow.controlplaneservice.domain.aggregates.route.vo.RouteId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Routes")
public class RouteCrudOrchestrator extends CrudOrchestrator<
        RouteViewModel,
        RouteViewModel,
        RouteViewModel,
        NoFilters,
        RouteRow,
        String
        > {

    final RouteRepository routeRepository;
    final RouteCrudAdapter adapter;

    @Override
    public CrudAdapter<RouteViewModel,
            RouteViewModel, RouteViewModel,
            NoFilters, RouteRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }

    public void changeHash(HttpRequest httpRequest) {
        var data = (Map<String, Object>) httpRequest.runActionRq().parameters().get("_clickedRow");
        var route = routeRepository.findById(new RouteId(Long.parseLong((String) data.get("id")))).orElseThrow();
        route.updateHash(new RouteHash(""));
        routeRepository.save(route);
    }

    @Override
    public List<Trigger> triggers(HttpRequest httpRequest) {
        var triggers = new ArrayList<>(super.triggers(httpRequest));
        triggers.add(new OnSuccessTrigger("search", "action-on-row-changeHash"));
        return triggers;
    }
}
