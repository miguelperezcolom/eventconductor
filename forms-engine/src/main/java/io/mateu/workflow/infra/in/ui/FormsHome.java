package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.MateuUI;
import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.Forms;

@MateuUI("/forms")
public class FormsHome {

    @Menu
    Forms forms;

}
