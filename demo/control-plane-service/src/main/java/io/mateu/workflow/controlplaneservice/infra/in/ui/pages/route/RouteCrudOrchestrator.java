package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.route;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.out.RouteRepository;
import io.mateu.workflow.controlplaneservice.application.query.RouteQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.RouteRow;
import io.mateu.workflow.controlplaneservice.application.usecases.route.delete.DeleteRouteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.route.delete.DeleteRouteUseCase;
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
public class RouteCrudOrchestrator extends Crud<
        RouteViewModel,
        RouteViewModel,
        RouteViewModel,
        NoFilters,
        RouteRow,
        String
        > {

    final RouteRepository routeRepository;
    final RouteViewModel viewModel;
    final DeleteRouteUseCase deleteRouteUseCase;
    final RouteQueryService queryService;

    @Override
    public ListingData<RouteRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public RouteViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public RouteViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public RouteViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(RouteViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(RouteViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteRouteUseCase.handle(new DeleteRouteCommand(selectedIds));
    }

    public void changeHash(HttpRequest httpRequest) {
        var data = (Map<String, Object>) httpRequest.runActionRq().parameters().get("_clickedRow");
        var route = routeRepository.findById(new RouteId(Long.parseLong((String) data.get("id")))).orElseThrow();
        route.updateHash(new RouteHash(""));
        routeRepository.save(route);
    }

    @Override
    public List<Trigger> triggers(String viewName, HttpRequest httpRequest) {
        var triggers = new ArrayList<>(super.triggers(viewName, httpRequest));
        triggers.add(new OnSuccessTrigger("search", "action-on-row-changeHash"));
        return triggers;
    }
}
