package io.mateu.workflow.shell.infra.in.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.Anchor;
import io.mateu.uidl.data.Popover;
import io.mateu.uidl.data.RemoteMenu;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.WidgetSupplier;

import java.util.Base64;
import java.util.List;

import static io.mateu.core.infra.JsonSerializer.fromJson;

@UI("")
@Title("Console")
@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
@FavIcon("/images/riu.svg")
@PageTitle("Console")
@Logo("/images/riu.svg")
public class ShellHome implements WidgetSupplier {

    @Menu
    RemoteMenu users = new RemoteMenu("/_users").withAppServerSideType("io.mateu.workflow.usersservice.infra.in.ui.UsersHome");

    @Menu
    RemoteMenu content = new RemoteMenu("/_content-service").withAppServerSideType("io.mateu.workflow.contentservice.infra.in.ui.ContentServiceHome");

    @Menu
    RemoteMenu controlPlane = new RemoteMenu("/_control-plane").withAppServerSideType("io.mateu.workflow.controlplaneservice.infra.in.ui.ControlPlaneHome");

    @Menu
    RemoteMenu workflow = new RemoteMenu("/_workflow").withAppServerSideType("io.mateu.workflow.infra.in.ui.WorkflowHome");

    @Override
    public java.util.List<Component> widgets(HttpRequest httpRequest) {
        if (httpRequest.getHeaderValue("Authorization") != null && httpRequest.getHeaderValue("Authorization").startsWith("Bearer ")) {

            var token = httpRequest.getHeaderValue("Authorization").substring("Bearer ".length());

            var payload = new String(Base64.getDecoder().decode(token.split("\\.")[1]));

            var values = fromJson(payload);

//            var claims = Jwts.parser()
//                    .build()
//                    .parse(token)
//                    .getPayload();

            return java.util.List.of(io.mateu.uidl.data.HorizontalLayout.builder().content(java.util.List.of(Popover.builder()
                    .wrapped(io.mateu.uidl.data.Text.builder().text("Hola, " + values.get("name"))
                            .style("margin-right: 20px;")
                            .build())
                    .content(io.mateu.uidl.data.VerticalLayout.builder().content(java.util.List.of(
                                    new io.mateu.uidl.data.Text("Email: " + values.get("email")),
                                    new Anchor("Logout", "javascript: window.logout();"))
                            ).spacing(true)
                            .padding(true)
                            .build())
                    .build())).build());
        }
        return List.of();
    }
}
