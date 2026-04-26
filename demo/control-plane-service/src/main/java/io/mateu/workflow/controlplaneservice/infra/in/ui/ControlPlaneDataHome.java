package io.mateu.workflow.controlplaneservice.infra.in.ui;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.FavIcon;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Logo;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.PageTitle;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.Card;
import io.mateu.uidl.data.Chart;
import io.mateu.uidl.data.ChartAxisScale;
import io.mateu.uidl.data.ChartOptions;
import io.mateu.uidl.data.ChartScales;
import io.mateu.uidl.data.ChartType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Text;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.PostHydrationHandler;
import io.mateu.workflow.controlplaneservice.infra.in.ui.adapters.ControlPlaneHomeAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@UI("/_cp-data")
@RequiredArgsConstructor
@Service
public class ControlPlaneDataHome {

    @Menu
    ControlPlaneMenu data;

}
