package io.mateu.workflow.usersservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.workflow.usersservice.application.query.PermissionQueryService;
import io.mateu.workflow.usersservice.domain.aggregates.permission.vo.PermissionId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PermissionIdLabelSupplier implements LabelSupplier {

    final PermissionQueryService queryService;

    @Override
    public String label(Object id, HttpRequest httpRequest) {
        return queryService.getLabel((String) id);
    }
}
