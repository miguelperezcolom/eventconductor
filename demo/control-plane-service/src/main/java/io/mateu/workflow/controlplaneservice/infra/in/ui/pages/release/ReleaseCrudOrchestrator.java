package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.ReleaseQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseRow;
import io.mateu.workflow.controlplaneservice.application.usecases.release.changestatus.ChangeReleaseStatusCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.release.changestatus.ChangeReleaseStatusUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.release.delete.DeleteReleaseCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.release.delete.DeleteReleaseUseCase;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Releases")
@ReadOnly
public class ReleaseCrudOrchestrator extends Crud<
        ReleaseViewModel,
        ReleaseViewModel,
        ReleaseViewModel,
        NoFilters,
        ReleaseRow,
        String
        > {

    final ReleaseViewModel viewModel;
    final DeleteReleaseUseCase deleteReleaseUseCase;
    final ReleaseQueryService queryService;
    final ChangeReleaseStatusUseCase changeReleaseStatusUseCase;

    @Override
    public ListingData<ReleaseRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public ReleaseViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public ReleaseViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public ReleaseViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(ReleaseViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(ReleaseViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteReleaseUseCase.handle(new DeleteReleaseCommand(selectedIds));
    }

    public void setAsBlue(HttpRequest httpRequest) {
        var data = (Map<String, Object>) httpRequest.runActionRq().parameters().get("_clickedRow");
        changeReleaseStatusUseCase
                .handle(new ChangeReleaseStatusCommand(
                        List.of((String) data.get("id")), "Blue"));
    }

    public void setAsGreen(HttpRequest httpRequest) {
        var data = (Map<String, Object>) httpRequest.runActionRq().parameters().get("_clickedRow");
        changeReleaseStatusUseCase
                .handle(new ChangeReleaseStatusCommand(
                        List.of((String) data.get("id")), "Green"));
    }

    @SneakyThrows
    public Object preview(HttpRequest httpRequest) {
        var data = (Map<String, Object>) httpRequest.runActionRq().parameters().get("_clickedRow");
        return URI.create("https://riu-com-copy.miguelperezcolom.workers.dev/es?force_version=v" + (String) data.get("id")).toURL();
    }

    @Override
    public List<Trigger> triggers(String viewName, HttpRequest httpRequest) {
        var triggers = new ArrayList<>(super.triggers(viewName, httpRequest));
        triggers.add(new OnSuccessTrigger("search", "action-on-row-setAsBlue"));
        triggers.add(new OnSuccessTrigger("search", "action-on-row-setAsGreen"));
        return triggers;
    }

    @Override
    public boolean selectionEnabled() {
        return false;
    }
}
