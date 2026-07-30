package io.mateu.workflow.usersservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LookupLabelSupplier;
import io.mateu.workflow.usersservice.application.query.UserGroupQueryService;
import io.mateu.workflow.usersservice.domain.aggregates.usergroup.vo.UserGroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserGroupIdLabelSupplier implements LookupLabelSupplier {

    final UserGroupQueryService queryService;

    @Override
    public String label(String fieldId, Object id, HttpRequest httpRequest) {
        return queryService.getLabel((String) id);
    }
}
