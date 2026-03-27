package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.route;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.controlplaneservice.application.query.dto.RouteRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

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
}
