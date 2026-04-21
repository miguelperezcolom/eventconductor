package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.country.CountryCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.environment.EnvironmentCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.language.LanguageCrudOrchestrator;
import io.mateu.workflow.controlplaneservice.infra.in.ui.pages.tier.TierCrudOrchestrator;

public class MasterDataMenu {

    @Menu
    EnvironmentCrudOrchestrator environments;
    @Menu
    LanguageCrudOrchestrator languages;
    @Menu
    CountryCrudOrchestrator countries;
    @Menu
    TierCrudOrchestrator tiers;
}
