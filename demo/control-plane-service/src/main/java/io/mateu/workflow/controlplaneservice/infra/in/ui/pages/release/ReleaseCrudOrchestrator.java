package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.release;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.dto.ReleaseRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Releases")
public class ReleaseCrudOrchestrator extends CrudOrchestrator<
        ReleaseViewModel,
        ReleaseViewModel,
        ReleaseViewModel,
        NoFilters,
        ReleaseRow,
        String
        > {

    final ReleaseCrudAdapter adapter;

    @Override
    public CrudAdapter<ReleaseViewModel,
            ReleaseViewModel, ReleaseViewModel,
            NoFilters, ReleaseRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }

    @ListToolbarButton
    public void setAsBlue(HttpRequest httpRequest) {}

    @ListToolbarButton
    public void setAsGreen(HttpRequest httpRequest) {}
}
