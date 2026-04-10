package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.RouteConstants;
import io.mateu.uidl.annotations.Route;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.Hydratable;
import io.mateu.workflow.infra.out.persistence.FormExecutionEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Route(value = "/" +
        "my-tasks", parentRoute = RouteConstants.NO_PARENT_ROUTE)
@Title("")
@Service
@RequiredArgsConstructor
public class TasksWidget implements Hydratable {

    final FormExecutionEntityRepository repository;

    @Stereotype(FieldStereotype.html)
    String content = "hello";

    @Override
    public void hydrate(HttpRequest httpRequest) {
        content = "xxxx";
    }
}
