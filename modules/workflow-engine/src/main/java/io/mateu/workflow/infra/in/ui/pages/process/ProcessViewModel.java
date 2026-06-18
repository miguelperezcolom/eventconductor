package io.mateu.workflow.infra.in.ui.pages.process;


import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.fluent.TriggersSupplier;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Named;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Errors;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Messages;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Resources;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Steps;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@NoArgsConstructor
@Getter
@ReadOnly
public class ProcessViewModel implements TriggersSupplier {

    String id;

    String name;

    Status status;

    @Tab
            @Label("")
    Callable<?> steps = () -> MateuBeanProvider.getBean(Steps.class).withProcessId(id);

    @Tab
    @Label("")
    Callable<?> messages = () -> MateuBeanProvider.getBean(Messages.class).withProcessId(id);

    @Tab
    @Label("")
    Callable<?> errors = () -> MateuBeanProvider.getBean(Errors.class).withProcessId(id);

    @Tab
    @Label("")
    Callable<?> resources = () -> MateuBeanProvider.getBean(Resources.class).withProcessId(id);

    public ProcessViewModel(String id, String name, Status status) {
        this.id = id;
        this.name = name;
        this.status = status;
    }

    @Override
    public String toString() {
        return "Process " + (id != null && id.length() > 5?"..." + id.substring(id.length() - 5):id) + " (old)";
    }

    @Toolbar
    public void cancel() {

    }

    @Override
    public List<Trigger> triggers(HttpRequest httpRequest) {
        var triggers = new ArrayList<Trigger>();
        triggers.add(new OnLoadTrigger("view", 3000, 1, "state.status.type != 'SUCCESS'"));
        //triggers.add(new OnLoadTrigger("refresh", 1000, 1, "state.status.type != 'SUCCESS'"));
        //triggers.add(new OnSuccessTrigger("refresh", "refresh", "state.status.type != 'SUCCESS'", 1000));
        return triggers;
    }
}
