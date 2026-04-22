package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseRow;
import io.mateu.workflow.controlplaneservice.application.usecases.release.changestatus.ChangeReleaseStatusCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.release.changestatus.ChangeReleaseStatusUseCase;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Releases")
@ReadOnly
public class ReleaseCrudOrchestrator extends CrudOrchestrator<
        ReleaseViewModel,
        ReleaseViewModel,
        ReleaseViewModel,
        NoFilters,
        ReleaseRow,
        String
        > {

    final ReleaseCrudAdapter adapter;
    final ChangeReleaseStatusUseCase changeReleaseStatusUseCase;

    @Override
    public CrudAdapter<ReleaseViewModel,
            ReleaseViewModel, ReleaseViewModel,
            NoFilters, ReleaseRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
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
    public List<Trigger> triggers(HttpRequest httpRequest) {
        var triggers = new ArrayList<>(super.triggers(httpRequest));
        triggers.add(new OnSuccessTrigger("search", "action-on-row-setAsBlue"));
        triggers.add(new OnSuccessTrigger("search","action-on-row-setAsGreen"));
        return triggers;
    }

    @Override
    public boolean selectionEnabled() {
        return false;
    }
}
