package io.mateu.workflow.contentservice.infra.in.ui.pages.content;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.contentservice.application.query.ContentQueryService;
import io.mateu.workflow.contentservice.application.query.dto.ContentRow;
import io.mateu.workflow.contentservice.application.usecases.content.delete.DeleteContentCommand;
import io.mateu.workflow.contentservice.application.usecases.content.delete.DeleteContentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Contents")
public class ContentCrudOrchestrator extends Crud<
        ContentViewModel,
        ContentViewModel,
        ContentViewModel,
        NoFilters,
        ContentRow,
        String
        > {

    final ContentViewModel viewModel;
    final DeleteContentUseCase deleteContentUseCase;
    final ContentQueryService queryService;

    @Override
    public ListingData<ContentRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public ContentViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public ContentViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public ContentViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(ContentViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(ContentViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteContentUseCase.handle(new DeleteContentCommand(selectedIds));
    }

    @Override
    public String getIdFieldForRow() {
        return "id";
    }
}
