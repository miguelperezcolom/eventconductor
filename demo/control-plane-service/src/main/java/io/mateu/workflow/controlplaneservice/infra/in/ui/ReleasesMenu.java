package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release.ReleaseCrudOrchestrator;

public class ReleasesMenu {

    @Menu
    ReleaseCrudOrchestrator changeControl;

    @Menu
    ReleaseCrudOrchestrator releases;

}
