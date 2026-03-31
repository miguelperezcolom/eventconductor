package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.changes.Changes;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.Deployer;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release.ReleaseCrudOrchestrator;

public class ControlPlaneMenu {

    @Menu
    MasterDataMenu masterData;

    @Menu
    SitesMenu sites;

    @Menu
    DownloadedContentMenu downloadedContent;

    @Menu
    ReleaseCrudOrchestrator releases;

    @Menu
    Changes changes;

    @Menu
    Deployer deployer;

}
