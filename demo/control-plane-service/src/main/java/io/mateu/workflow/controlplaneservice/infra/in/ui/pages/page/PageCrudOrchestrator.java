package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.controlplaneservice.application.query.dto.PageRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Pages")
public class PageCrudOrchestrator extends CrudOrchestrator<
        PageViewModel,
        PageViewModel,
        PageViewModel,
        NoFilters,
        PageRow,
        String
        > {

    final PageCrudAdapter adapter;

    @Override
    public CrudAdapter<PageViewModel,
            PageViewModel, PageViewModel,
            NoFilters, PageRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}
