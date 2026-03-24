package io.mateu.workflow.contentservice.infra.in.ui.pages.label;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.contentservice.application.query.dto.LabelRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Labels")
public class LabelCrudOrchestrator extends CrudOrchestrator<
LabelViewModel,
LabelViewModel,
LabelViewModel,
NoFilters,
LabelRow,
String
> {

final LabelCrudAdapter adapter;

@Override
public CrudAdapter<LabelViewModel,
LabelViewModel, LabelViewModel,
NoFilters, LabelRow, String> adapter() {
return adapter;
}

@Override
public String toId(String s) {
return s;
}
}
