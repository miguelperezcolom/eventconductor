package io.mateu.workflow.contentservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.workflow.contentservice.application.query.ContentTypeQueryService;
import io.mateu.workflow.contentservice.domain.aggregates.contenttype.vo.ContentTypeId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContentTypeIdLabelSupplier implements LabelSupplier {

final ContentTypeQueryService queryService;

@Override
public String label(Object id, HttpRequest httpRequest) {
return queryService.getLabel((String) id);
}
}
