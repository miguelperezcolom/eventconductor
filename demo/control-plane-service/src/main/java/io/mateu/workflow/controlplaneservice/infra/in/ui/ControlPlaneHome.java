package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;

@UI("/_control-plane")
@Title("Control plane")
public class ControlPlaneHome {

    @Menu
    ControlPlaneMenu controlPlane;

}
