package io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LookupLabelSupplier;
import io.mateu.workflow.controlplaneservice.application.query.PageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PageIdLabelSupplier implements LookupLabelSupplier {

    final PageQueryService queryService;

    @Override
    public String label(String fieldId, Object id, HttpRequest httpRequest) {
        return queryService.getLabel((String) id);
    }
}
