package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page.PageCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.site.SiteCrudOrchestrator;

public class SitesMenu {

    @Menu
    SiteCrudOrchestrator sites;
    @Menu
    PageCrudOrchestrator pages;


}
