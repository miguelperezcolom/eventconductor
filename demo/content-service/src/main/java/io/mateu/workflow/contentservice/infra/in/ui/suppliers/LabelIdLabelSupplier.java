package io.mateu.workflow.contentservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LookupLabelSupplier;
import io.mateu.workflow.contentservice.application.query.LabelQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LabelIdLabelSupplier implements LookupLabelSupplier {

final LabelQueryService queryService;

@Override
public String label(String fieldId, Object id, HttpRequest httpRequest) {
return queryService.getLabel((String) id);
}
}
