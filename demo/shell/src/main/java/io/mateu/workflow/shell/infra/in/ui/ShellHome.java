package io.mateu.workflow.shell.infra.in.ui;

import io.mateu.uidl.annotations.EyesOnly;
import io.mateu.uidl.annotations.FavIcon;
import io.mateu.uidl.annotations.KeycloakSecured;
import io.mateu.uidl.annotations.Logo;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.PageTitle;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.Anchor;
import io.mateu.uidl.data.HorizontalLayout;
import io.mateu.uidl.data.Popover;
import io.mateu.uidl.data.RemoteMenu;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.data.VerticalLayout;
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

    //@EyesOnly(roles = "admin")
    @Menu
    RemoteMenu users = new RemoteMenu("/_users").withAppServerSideType("io.mateu.workflow.usersservice.infra.in.ui.UsersHome");


    //@EyesOnly(roles = {"admin", "operator"})
    @Menu
    RemoteMenu content = new RemoteMenu("/_content-service").withAppServerSideType("io.mateu.workflow.contentservice.infra.in.ui.ContentServiceHome");

    //@EyesOnly(roles = {"admin", "operator"})
    @Menu
    RemoteMenu controlPlane = new RemoteMenu("/_control-plane").withAppServerSideType("io.mateu.workflow.controlplaneservice.infra.in.ui.ControlPlaneHome");

    //@EyesOnly(scopes = {"workflow:read"})
    @Menu
    RemoteMenu workflow = new RemoteMenu("/_workflow").withAppServerSideType("io.mateu.workflow.infra.in.ui.WorkflowHome");

    @Menu
    CheckRequest checkRequest;

    @Override
    public List<Component> widgets(HttpRequest httpRequest) {
        if (httpRequest.getHeaderValue("Authorization") != null
                && httpRequest.getHeaderValue("Authorization").startsWith("Bearer ")) {

            var token = httpRequest.getHeaderValue("Authorization").substring("Bearer ".length());

            var payload = new String(Base64.getDecoder().decode(token.split("\\.")[1]));

            var values = fromJson(payload);

            return List.of(HorizontalLayout.builder().content(List.of(Popover.builder()
                    .wrapped(Text.builder().text("Hola, " + values.get("name"))
                            .style("margin-right: 20px;")
                            .build())
                    .content(VerticalLayout.builder().content(List.of(
                                    new Text("Email: " + values.get("email")),
                                    new Anchor("Logout", "javascript: window.logout();"))
                            ).spacing(true)
                            .padding(true)
                            .build())
                    .build())).build());
        }
        return List.of();
    }
}
