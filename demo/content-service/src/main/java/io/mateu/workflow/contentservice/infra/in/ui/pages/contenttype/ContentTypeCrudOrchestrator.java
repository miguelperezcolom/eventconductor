package io.mateu.workflow.contentservice.infra.in.ui.pages.contenttype;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.contentservice.application.query.dto.ContentTypeRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("ContentTypes")
public class ContentTypeCrudOrchestrator extends CrudOrchestrator<
ContentTypeViewModel,
ContentTypeViewModel,
ContentTypeViewModel,
NoFilters,
ContentTypeRow,
String
> {

final ContentTypeCrudAdapter adapter;

@Override
public CrudAdapter<ContentTypeViewModel,
ContentTypeViewModel, ContentTypeViewModel,
NoFilters, ContentTypeRow, String> adapter() {
return adapter;
}

@Override
public String toId(String s) {
return s;
}
}
