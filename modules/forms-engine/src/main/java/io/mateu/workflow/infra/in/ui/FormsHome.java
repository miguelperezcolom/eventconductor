package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.pages.Forms;

@UI("/forms")
public class FormsHome {

    @Menu
    Forms forms;

}
