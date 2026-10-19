package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.JwtExtractor;
import io.mateu.uidl.RouteConstants;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Div;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.State;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.ComponentTreeSupplier;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Hydratable;
import io.mateu.workflow.infra.out.persistence.FormExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@UI(value = "/_forms/my-tasks")
@Title("")
@Service
@RequiredArgsConstructor
@Trigger(type = TriggerType.OnLoad, actionId = "refreshTasks", timeoutMillis = 5000)
@Trigger(type = TriggerType.OnSuccess, actionId = "refreshTasks", calledActionId = "refreshTasks", timeoutMillis = 5000)
@Action(id = "refreshTasks")
public class TasksWidget implements Hydratable, ComponentTreeSupplier {

    final io.mateu.workflow.application.services.TaskInbox taskInbox;
    final io.mateu.workflow.application.services.TaskCallers taskCallers;

    String content = "hello";


    Object refreshTasks() {
        return new State(this);
    }

    @Override
    public void hydrate(HttpRequest httpRequest) {

        // The badge is a listing with one number in it, so it counts what the inbox would list for
        // this person — what the tasks name and what their forms require: counting tasks they may not
        // work on would tell them the work exists, which is most of what hiding it was for.
        var caller = taskCallers.forUser(JwtExtractor.getUsername(httpRequest).orElse(null));
        var tasks = taskInbox.count(io.mateu.workflow.domain.TaskQuery.open(), caller);
        var overdue = taskInbox.count(new io.mateu.workflow.domain.TaskQuery(io.mateu.workflow.domain.TaskQuery.Scope.ALL,
                null, null, Boolean.TRUE, null, null, null, null, null, 0, 1), caller);

        /*
        route("/forms/tasks")
                                    .consumedRoute("")
                                    .baseUrl("/_forms")
                                    .uriPrefix("")
                                    .serverSideType("io.mateu.workflow.infra.in.ui.FormsHome")
         */

        String navonclick = "event.preventDefault(); this.dispatchEvent(new CustomEvent('navigation-requested', {" +
                "detail: {" +
                "route: '/forms/tasks'," +
                "consumedRoute: ''," +
                "baseUrl: '/_forms'," +
                "uriPrefix: ''," +
                "serverSideType: 'io.mateu.workflow.infra.in.ui.FormsHome'" +
                "}," +
                "bubbles: true," +
                "composed: true" +
                "}))";

        if (tasks > 0) {
            content = "<a href=\"#\" onclick=\"" + navonclick + "\" style=\"" +
                    "text-decoration: none;" +
                    "animation: fade 2s ease-in-out infinite alternate;" +
                    "\">" +
                    (overdue > 0 ? "You have " + tasks + " tasks (" + overdue + " overdue)!" : "You have tasks!") +
                    "</a>&nbsp;&nbsp;" +
                    "<style>" +
                    "@keyframes fade { from { opacity: 1; } to { opacity: 0; } }" +
                    "</style>";
        } else {
            content = "<a href=\"#\" onclick=\"" + navonclick + "\" style=\"text-decoration: none;\">No tasks</a>&nbsp;&nbsp;";
        }
    }

    @Override
    public Component component(HttpRequest httpRequest) {
        return Text.builder()
                .text("${state.content}")
                .build();
    }
}
