package io.mateu.workflow.rulesembeddedmvc.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.RulesMenu;

@UI("")
public class Home {

    @Menu
    RulesMenu rules;

}
