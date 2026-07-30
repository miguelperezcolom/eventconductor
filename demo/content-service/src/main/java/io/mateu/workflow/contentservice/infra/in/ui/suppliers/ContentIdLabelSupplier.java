package io.mateu.workflow.contentservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LookupLabelSupplier;
import io.mateu.workflow.contentservice.application.query.ContentQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContentIdLabelSupplier implements LookupLabelSupplier {

    final ContentQueryService queryService;

    @Override
    public String label(String fieldName, Object id, HttpRequest httpRequest) {
        return queryService.getLabel((String) id);
    }
}
