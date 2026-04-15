package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.resource;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
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
@Scope("prototype")
@RequiredArgsConstructor
public class ResourceCrudAdapter implements CrudAdapter<
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
    public ListingData<ResourceRow> search(String searchText,
                                           NoFilters filters,
                                           Pageable pageable, HttpRequest httpRequest) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteResourceUseCase.handle(new DeleteResourceCommand(selectedIds));
    }

    @Override
    public ResourceViewModel getView(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public ResourceViewModel getEditor(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public ResourceViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
