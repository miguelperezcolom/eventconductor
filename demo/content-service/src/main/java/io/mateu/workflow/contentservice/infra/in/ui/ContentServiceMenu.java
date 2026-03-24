package io.mateu.workflow.contentservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.contentservice.infra.in.ui.pages.JsonLD;
import io.mateu.workflow.contentservice.infra.in.ui.pages.LlmsTxt;

public class ContentServiceMenu {

    @Menu
    JsonLD jsonld;

    @Menu
    LlmsTxt llmsTxt;

}
