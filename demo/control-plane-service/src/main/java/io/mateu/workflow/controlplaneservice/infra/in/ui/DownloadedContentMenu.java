package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.asset.AssetCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.resource.ResourceCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.route.RouteCrudOrchestrator;

public class DownloadedContentMenu {

    @Menu
    RouteCrudOrchestrator routes;
    @Menu
    AssetCrudOrchestrator assets;
    @Menu
    ResourceCrudOrchestrator resources;

}
