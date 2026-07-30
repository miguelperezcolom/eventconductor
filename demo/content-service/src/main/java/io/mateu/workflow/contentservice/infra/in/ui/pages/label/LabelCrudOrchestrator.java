package io.mateu.workflow.contentservice.infra.in.ui.pages.label;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.contentservice.application.query.LabelQueryService;
import io.mateu.workflow.contentservice.application.query.dto.LabelRow;
import io.mateu.workflow.contentservice.application.usecases.label.delete.DeleteLabelCommand;
import io.mateu.workflow.contentservice.application.usecases.label.delete.DeleteLabelUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Labels")
public class LabelCrudOrchestrator extends Crud<
        LabelViewModel,
        LabelViewModel,
        LabelViewModel,
        NoFilters,
        LabelRow,
        String
        > {

    final LabelViewModel viewModel;
    final DeleteLabelUseCase deleteLabelUseCase;
    final LabelQueryService queryService;

    @Override
    public ListingData<LabelRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public LabelViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public LabelViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public LabelViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(LabelViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(LabelViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteLabelUseCase.handle(new DeleteLabelCommand(selectedIds));
    }

    @Override
    public String getIdFieldForRow() {
        return "id";
    }
}
