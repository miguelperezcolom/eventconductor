package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.asset.AssetCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.assetversion.AssetVersionCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.country.CountryCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.environment.EnvironmentCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.language.LanguageCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page.PageCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release.ReleaseCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.resource.ResourceCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.route.RouteCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site.SiteCrudOrchestrator;

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
