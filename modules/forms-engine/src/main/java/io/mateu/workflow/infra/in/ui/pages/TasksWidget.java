package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.JwtExtractor;
import io.mateu.uidl.RouteConstants;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.State;
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
public class TasksWidget implements Hydratable {

    final FormExecutionEntityRepository repository;

    @Stereotype(FieldStereotype.html)
    String content = "hello";

    Object refreshTasks() {
        return new State(this);
    }

    @Override
    public void hydrate(HttpRequest httpRequest) {

        var tasks = repository.findAll().stream()
                .filter(task -> !"COMPLETED".equals(task.getStatus())
                        && !"ERROR".equals(task.getStatus())
                        && (
                                !"ASSIGNED".equals(task.getStatus()) && (task.getUserId() == null
                                        || task.getUserId().isEmpty())
                                        || (task.getUserId() != null && task.getUserId().equals(JwtExtractor.getUsername(httpRequest).orElse("xxx")))
                        )
                )
                .count();

        if (tasks > 0) {
            content = "<a href=\"/forms/tasks\" style=\"\n" +
                    "    text-decoration: none;\n" +
                    "    xcolor: #3498db;\n" +
                    "    xfont-weight: bold;\n" +
                    "    animation: fade 2s ease-in-out infinite alternate;\n" +
                    "\">\n" +
                    "    You have tasks!\n" +
                    "</a>&nbsp;&nbsp;\n" +
                    "\n" +
                    "<style>\n" +
                    "    @keyframes fade {\n" +
                    "        from { opacity: 1; }\n" +
                    "        to { opacity: 0; }\n" +
                    "    }\n" +
                    "</style>";

        } else {
            content = "<a href=\"/forms/tasks\" style=\"text-decoration: none;\">No tasks</a>&nbsp;&nbsp;";
        }
    }
}
