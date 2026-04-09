package io.mateu.workflow.controlplaneservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.workflow.controlplaneservice.application.query.AssetQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AssetIdLabelSupplier implements LabelSupplier {

    final AssetQueryService queryService;

    @Override
    public String label(String fieldId, Object id, HttpRequest httpRequest) {
        return queryService.getLabel((String) id);
    }
}
