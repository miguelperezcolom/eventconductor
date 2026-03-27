package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;

public class ControlPlaneMenu {

    @Menu
    MasterDataMenu masterData;

    @Menu
    SitesMenu sites;

    @Menu
    DownloadedContentMenu downloadedContent;

    @Menu
    ReleasesMenu releases;


}
