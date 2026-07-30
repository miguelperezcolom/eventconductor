package io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LookupLabelSupplier;
import io.mateu.workflow.controlplaneservice.application.query.EnvironmentQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EnvironmentIdLabelSupplier implements LookupLabelSupplier {

    final EnvironmentQueryService queryService;

    @Override
    public String label(String fieldId, Object id, HttpRequest httpRequest) {
        return queryService.getLabel((String) id);
    }
}
