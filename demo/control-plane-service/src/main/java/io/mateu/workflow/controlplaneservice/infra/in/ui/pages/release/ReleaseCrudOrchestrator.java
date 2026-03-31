package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.TriggerType;
import io.mateu.uidl.annotations.ViewToolbarButton;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseRow;
import io.mateu.workflow.controlplaneservice.application.usecases.release.changestatus.ChangeReleaseStatusCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.release.changestatus.ChangeReleaseStatusUseCase;
import io.mateu.workflow.controlplaneservice.application.usecases.route.downloadassets.DownloadAssetsCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.site.scrap.ScrapCommand;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.process.Step;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site.SiteViewModel;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Releases")
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

    @Override
    public List<Trigger> triggers(HttpRequest httpRequest) {
        var triggers = new ArrayList<>(super.triggers(httpRequest));
        triggers.add(new OnSuccessTrigger("search", "action-on-row-setAsBlue"));
        triggers.add(new OnSuccessTrigger("search","action-on-row-setAsGreen"));
        return triggers;
    }
}
