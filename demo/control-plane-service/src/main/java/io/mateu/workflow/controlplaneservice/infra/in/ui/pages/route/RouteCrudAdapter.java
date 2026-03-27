package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.route;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.RouteQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.RouteRow;
import io.mateu.workflow.controlplaneservice.application.usecases.route.delete.DeleteRouteCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.route.delete.DeleteRouteUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class RouteCrudAdapter implements CrudAdapter<
        RouteViewModel,
        RouteViewModel,
        RouteViewModel,
        NoFilters,
        RouteRow,
        String
        > {

    final RouteViewModel viewModel;
    final DeleteRouteUseCase deleteRouteUseCase;
    final RouteQueryService queryService;

    @Override
    public ListingData<RouteRow> search(String searchText,
                                        NoFilters filters,
                                        Pageable pageable) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        deleteRouteUseCase.handle(new DeleteRouteCommand(selectedIds));
    }

    @Override
    public RouteViewModel getView(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public RouteViewModel getEditor(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public RouteViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
