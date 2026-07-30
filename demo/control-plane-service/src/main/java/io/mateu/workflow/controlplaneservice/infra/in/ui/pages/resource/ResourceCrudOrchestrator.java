package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.resource;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.ResourceQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ResourceRow;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.delete.DeleteResourceCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.resource.delete.DeleteResourceUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Resources")
public class ResourceCrudOrchestrator extends Crud<
        ResourceViewModel,
        ResourceViewModel,
        ResourceViewModel,
        NoFilters,
        ResourceRow,
        String
        > {

    final ResourceViewModel viewModel;
    final DeleteResourceUseCase deleteResourceUseCase;
    final ResourceQueryService queryService;

    @Override
    public ListingData<ResourceRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public ResourceViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public ResourceViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public ResourceViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(ResourceViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(ResourceViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteResourceUseCase.handle(new DeleteResourceCommand(selectedIds));
    }
}
