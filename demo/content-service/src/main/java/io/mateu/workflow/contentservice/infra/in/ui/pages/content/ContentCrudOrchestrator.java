package io.mateu.workflow.contentservice.infra.in.ui.pages.content;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.contentservice.application.query.dto.ContentRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Contents")
public class ContentCrudOrchestrator extends CrudOrchestrator<
ContentViewModel,
ContentViewModel,
ContentViewModel,
NoFilters,
ContentRow,
String
> {

final ContentCrudAdapter adapter;

@Override
public CrudAdapter<ContentViewModel,
ContentViewModel, ContentViewModel,
NoFilters, ContentRow, String> adapter() {
return adapter;
}

@Override
public String toId(String s) {
return s;
}
}
