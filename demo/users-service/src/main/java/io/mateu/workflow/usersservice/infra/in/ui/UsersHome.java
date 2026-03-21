package io.mateu.workflow.usersservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.usersservice.infra.in.ui.pages.permissions.PermissionsCrudOrchestrator;

@UI("")
public class UsersHome {

    @Menu
    PermissionsCrudOrchestrator permissions;

}
