package io.mateu.workflow.contentservice.infra.in.ui.pages.contenttype;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.contentservice.application.query.ContentTypeQueryService;
import io.mateu.workflow.contentservice.application.query.dto.ContentTypeRow;
import io.mateu.workflow.contentservice.application.usecases.contenttype.delete.DeleteContentTypeCommand;
import io.mateu.workflow.contentservice.application.usecases.contenttype.delete.DeleteContentTypeUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("ContentTypes")
public class ContentTypeCrudOrchestrator extends Crud<
        ContentTypeViewModel,
        ContentTypeViewModel,
        ContentTypeViewModel,
        NoFilters,
        ContentTypeRow,
        String
        > {

    final ContentTypeViewModel viewModel;
    final DeleteContentTypeUseCase deleteContentTypeUseCase;
    final ContentTypeQueryService queryService;

    @Override
    public ListingData<ContentTypeRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public ContentTypeViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public ContentTypeViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public ContentTypeViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(ContentTypeViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(ContentTypeViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteContentTypeUseCase.handle(new DeleteContentTypeCommand(selectedIds));
    }

    @Override
    public String getIdFieldForRow() {
        return "id";
    }
}
