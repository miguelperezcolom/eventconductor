package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.changes.Changes;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.country.CountryCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.deployment.Deployer;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.environment.EnvironmentCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.language.LanguageCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release.ReleaseCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.tier.TierCrudOrchestrator;

public class ReleasesMenu {

    @Menu
    Changes changes;

    @Menu
    ReleaseCrudOrchestrator releases;

    @Menu
    Deployer deployer;

}
