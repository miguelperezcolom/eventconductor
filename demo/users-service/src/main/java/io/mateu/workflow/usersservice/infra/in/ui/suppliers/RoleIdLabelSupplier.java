package io.mateu.workflow.usersservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.workflow.usersservice.application.query.RoleQueryService;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoleIdLabelSupplier implements LabelSupplier {

    final RoleQueryService queryService;

    @Override
    public String label(Object id, HttpRequest httpRequest) {
        return queryService.getLabel(new RoleId((String) id));
    }
}
