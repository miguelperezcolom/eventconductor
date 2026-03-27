package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.language;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.controlplaneservice.application.query.dto.LanguageRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Languages")
public class LanguageCrudOrchestrator extends CrudOrchestrator<
        LanguageViewModel,
        LanguageViewModel,
        LanguageViewModel,
        NoFilters,
        LanguageRow,
        String
        > {

    final LanguageCrudAdapter adapter;

    @Override
    public CrudAdapter<LanguageViewModel,
            LanguageViewModel, LanguageViewModel,
            NoFilters, LanguageRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}
