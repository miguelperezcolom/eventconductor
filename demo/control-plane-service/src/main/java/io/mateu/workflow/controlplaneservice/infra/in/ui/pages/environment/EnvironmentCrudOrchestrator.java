package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.environment;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.EnvironmentQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.EnvironmentRow;
import io.mateu.workflow.controlplaneservice.application.usecases.environment.delete.DeleteEnvironmentCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.environment.delete.DeleteEnvironmentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Environments")
public class EnvironmentCrudOrchestrator extends Crud<
        EnvironmentViewModel,
        EnvironmentViewModel,
        EnvironmentViewModel,
        NoFilters,
        EnvironmentRow,
        String
        > {

    final EnvironmentViewModel viewModel;
    final DeleteEnvironmentUseCase deleteEnvironmentUseCase;
    final EnvironmentQueryService queryService;

    @Override
    public ListingData<EnvironmentRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public EnvironmentViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public EnvironmentViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public EnvironmentViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(EnvironmentViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(EnvironmentViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteEnvironmentUseCase.handle(new DeleteEnvironmentCommand(selectedIds));
    }
}
