package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.RouteConstants;
import io.mateu.uidl.annotations.Route;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Hydratable;
import io.mateu.workflow.infra.out.persistence.FormExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@UI(value = "/_forms/my-tasks")
@Title("")
@Service
@RequiredArgsConstructor
public class TasksWidget implements Hydratable {

    final FormExecutionEntityRepository repository;

    @Stereotype(FieldStereotype.html)
    String content = "hello";

    @Override
    public void hydrate(HttpRequest httpRequest) {
        content = "<a href=\"/forms/tasks\" style=\"\n" +
                "    text-decoration: none;\n" +
                "    xcolor: #3498db;\n" +
                "    xfont-weight: bold;\n" +
                "    animation: fade 2s ease-in-out infinite alternate;\n" +
                "\">\n" +
                "    You have tasks!\n" +
                "</a>\n" +
                "\n" +
                "<style>\n" +
                "    @keyframes fade {\n" +
                "        from { opacity: 1; }\n" +
                "        to { opacity: 0; }\n" +
                "    }\n" +
                "</style>";
    }
}
