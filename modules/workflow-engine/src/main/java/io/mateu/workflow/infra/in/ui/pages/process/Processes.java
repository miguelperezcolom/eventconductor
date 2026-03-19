package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.core.infra.declarative.AutoCrudOrchestrator;
import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.NoCreationForm;
import io.mateu.uidl.data.NoEditor;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.infra.in.ui.adapters.ProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import io.mateu.workflow.domain.aggregates.Process;

import java.util.ArrayList;
import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends CrudOrchestrator<ProcessViewModel, NoEditor<String>, NoCreationForm<String>, NoFilters, ProcessRow, String> {

    final ProcessCrudAdapter processCrudAdapter;
    final CreateProcessForm createProcessForm;


    @Override
    public CrudAdapter<ProcessViewModel, NoEditor<String>, NoCreationForm<String>, NoFilters, ProcessRow, String> adapter() {
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
            triggers.add(new OnSuccessTrigger("view", "view", "state.status.type != 'SUCCESS'", 1000));
            return triggers;
        }
        return super.triggers(httpRequest);
    }
}
