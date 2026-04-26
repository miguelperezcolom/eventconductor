package io.mateu.workflow.shell.infra.in.ui;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.data.*;
import io.mateu.uidl.data.HorizontalLayout;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.WidgetSupplier;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.mateu.core.infra.JsonSerializer.fromJson;

@UI("")
@Title("Console")
@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
@FavIcon("/images/riu.svg")
@PageTitle("Home")
@Logo("/images/riu.svg")
@Style(StyleConstants.CONTAINER)
public class ShellHome implements WidgetSupplier
{

    @Menu
    RemoteMenu users = new RemoteMenu("/_users");


    @Menu
    RemoteMenu content = new RemoteMenu("/_content-service");

    @Menu
    RemoteMenu controlPlane = new RemoteMenu("/_control-plane");

    @Menu
        RemoteMenu workflow = new RemoteMenu("/_workflow");

    @Menu
    RemoteMenu forms = new RemoteMenu("/_forms");

    @Menu
    CheckRequest checkRequest;

    /*
    {"appServerSideType":"io.mateu.workflow.infra.in.ui.WorkflowHome",
    "appState":{},
    "initiatorComponentId":"ux_2deb36bd-131e-430a-a5ac-a763f7e2400d",
    "consumedRoute":"",
    "route":"/_page",
    "actionId":""}
     */

//    MicroFrontend dashboard = MicroFrontend.builder()
//            .baseUrl("/_workflow")
//            .appServerSideType("io.mateu.workflow.infra.in.ui.WorkflowHome")
//            .route("/_page")
//            .build();

    MicroFrontend dashboard = MicroFrontend.builder()
            .baseUrl("/_control-plane")
            .appServerSideType("io.mateu.workflow.controlplaneservice.infra.in.ui.ControlPlaneHome")
            .route("/_page")
            .build();

    @Override
    public List<Component> widgets(HttpRequest httpRequest) {

        List<Component> widgets = new ArrayList<>();

        if (httpRequest.getHeaderValue("Authorization") != null
                && httpRequest.getHeaderValue("Authorization").startsWith("Bearer ")) {


            var token = httpRequest.getHeaderValue("Authorization").substring("Bearer ".length());

            var payload = new String(Base64.getDecoder().decode(token.split("\\.")[1]));

            var values = fromJson(payload);

            widgets.add(HorizontalLayout.builder().content(List.of(MicroFrontend.builder()
                    .baseUrl("/_forms")
                    .route("/my-tasks")
                    .build(), Popover.builder()
                    .wrapped(Text.builder().text("Hola, " + values.get("name"))
                            .style("margin-right: 20px;")
                            .build())
                    .content(VerticalLayout.builder().content(List.of(
                                    new Text("Email: " + values.get("email")),
                                    new Anchor("Logout", "javascript: window.logout();"))
                            ).spacing(true)
                            .padding(true)
                            .build())
                    .build()))
                            .style("align-items: flex-end;")
                    .build());
        }
        return widgets;
    }
}
