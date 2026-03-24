package io.mateu.workflow.contentservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.contentservice.infra.in.ui.pages.content.ContentCrudOrchestrator;
import io.mateu.workflow.contentservice.infra.in.ui.pages.contenttype.ContentTypeCrudOrchestrator;
import io.mateu.workflow.contentservice.infra.in.ui.pages.label.LabelCrudOrchestrator;

public class ContentServiceMenu {

    @Menu
    ContentCrudOrchestrator contents;
    @Menu
    LabelCrudOrchestrator labels;
    @Menu
    ContentTypeCrudOrchestrator contentTypes;

}
