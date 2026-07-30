package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.interfaces.CrudStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
public class Rules extends AutoCrud<RuleRow> {

    final RuleRowsRepository ruleRowsRepository;

    @Override
    public CrudStore<RuleRow> store() {
        return ruleRowsRepository;
    }
}
