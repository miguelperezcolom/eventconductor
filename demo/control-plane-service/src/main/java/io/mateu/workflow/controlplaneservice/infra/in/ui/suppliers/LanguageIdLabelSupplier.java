package io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.workflow.controlplaneservice.application.query.LanguageQueryService;
import io.mateu.workflow.controlplaneservice.domain.aggregates.language.vo.LanguageId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LanguageIdLabelSupplier implements LabelSupplier {

final LanguageQueryService queryService;

@Override
public String label(Object id, HttpRequest httpRequest) {
return queryService.getLabel((String) id);
}
}
