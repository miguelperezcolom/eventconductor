package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.changes.Changes;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.Deployer;

public class ControlPlaneMenu {

    @Menu
    MasterDataMenu masterData;

    @Menu
    SitesMenu sites;

    @Menu
    DownloadedContentMenu downloadedContent;

    @Menu
    ReleasesMenu releases;

    @Menu
    Changes changes;

    @Menu
    Deployer deployer;

}
