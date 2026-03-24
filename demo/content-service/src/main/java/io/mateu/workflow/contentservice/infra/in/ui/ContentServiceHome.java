package io.mateu.workflow.contentservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;

@UI("/_content-service")
public class ContentServiceHome {

    @Menu
    ContentServiceMenu content;

}
