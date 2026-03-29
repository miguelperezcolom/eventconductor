package io.mateu.workflow.infra.in.ui.pages.process;


import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Tab;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.Status;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.fluent.OnLoadTrigger;
import io.mateu.uidl.fluent.OnSuccessTrigger;
import io.mateu.uidl.fluent.Trigger;
import io.mateu.uidl.fluent.TriggersSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Errors;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Message;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Messages;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Resource;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Resources;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Step;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Steps;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Error;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@NoArgsConstructor
@Getter
@ReadOnly
public class SimpleProcessViewModel implements TriggersSupplier {

    String id;

    String name;

    Status status;

    @Tab
    @Label("")
    List<Step> steps;

    @Tab
    @Label("")
    List<Message> messages;

    @Tab
    @Label("")
    List<Error> errors;

    @Tab
    @Label("")
    List<Resource> resources;

    public SimpleProcessViewModel(String id, String name, Status status, List<Step> steps, List<Message> messages, List<Error> errors, List<Resource> resources) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.steps = steps;
        this.messages = messages;
        this.errors = errors;
        this.resources = resources;
    }

    @Override
    public String toString() {
        return "Process " + id;
    }

    @Toolbar
    public void cancel() {

    }

    @Override
    public List<Trigger> triggers(HttpRequest httpRequest) {
        var triggers = new ArrayList<Trigger>();
        triggers.add(new OnLoadTrigger("refresh", 1000, 1, "state.status.type != 'SUCCESS'"));
        triggers.add(new OnSuccessTrigger("refresh", "refresh", "state.status.type != 'SUCCESS'", 1000));
        return triggers;
    }
}
