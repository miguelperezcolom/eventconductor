package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.core.infra.declarative.AutoCrudOrchestrator;
import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.dtos.UIFragmentDto;
import io.mateu.dtos.UIIncrementDto;
import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.*;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.in.ui.adapters.ProcessCrudAdapter;
import io.mateu.workflow.infra.in.ui.adapters.SimpleProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import io.mateu.workflow.domain.aggregates.Process;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends CrudOrchestrator<SimpleProcessViewModel, NoEditor<String>, NoCreationForm<String>, NoFilters, ProcessRow, String> {

    final SimpleProcessCrudAdapter processCrudAdapter;
    final CreateProcessForm createProcessForm;
    final ProcessRepository processRepository;


    @Override
    public CrudAdapter<SimpleProcessViewModel, NoEditor<String>, NoCreationForm<String>, NoFilters, ProcessRow, String> adapter() {
        return processCrudAdapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }

    @ListToolbarButton(rowsSelectedRequired = false)
    public CreateProcessForm create() {
        return createProcessForm;
    }

    @Override
    public List<Trigger> triggers(HttpRequest httpRequest) {
        if (isViewing(httpRequest)) {
            var triggers = new ArrayList<Trigger>(super.triggers(httpRequest));
            triggers.add(new OnLoadTrigger("view", 1000, 1, "state.status.type != 'SUCCESS'"));
            //triggers.add(new OnLoadTrigger("refresh", 5000, 1, "state.status.type != 'SUCCESS'"));
            //triggers.add(new OnSuccessTrigger("refresh", "refresh", "state.status.type != 'SUCCESS'", 5000));
            return triggers;
        }
        return super.triggers(httpRequest);
    }

    @Override
    public Object handleAction(String actionId, HttpRequest httpRequest) {
        var result = super.handleAction(actionId, httpRequest);
        if ("view".equals(httpRequest.runActionRq().actionId())) {
            var id = (String) httpRequest.runActionRq().componentState().get("id");
            if (id != null) {
                Process process = processRepository.findById(id).orElse(processRepository.findByBusinessKey(id).orElse(null));
                if (ProcessStatus.COMPLETED.equals(process.getStatus())) {
                    var returnTo = (String) httpRequest.runActionRq().componentState().get("returnTo");
                    if (returnTo == null) {
                        if (httpRequest.runActionRq().route().contains("returnTo")) {
                            returnTo = httpRequest.runActionRq().route().substring(httpRequest.runActionRq().route().indexOf("returnTo=")+"returnTo=".length());
                        }
                    }
                    if (returnTo != null) {
                        return URI.create(returnTo);
                    }
                }
            }
        }
        return result;
    }
}
