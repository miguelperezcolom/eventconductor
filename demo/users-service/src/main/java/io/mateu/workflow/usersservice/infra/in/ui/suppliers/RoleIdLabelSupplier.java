package io.mateu.workflow.usersservice.infra.in.ui.suppliers;

import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.uidl.interfaces.LabelSupplier;
import io.mateu.workflow.usersservice.application.out.RoleRepository;
import io.mateu.workflow.usersservice.domain.aggregates.role.Role;
import io.mateu.workflow.usersservice.domain.aggregates.role.vo.RoleId;
import io.mateu.workflow.usersservice.domain.aggregates.shared.vo.Name;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoleIdLabelSupplier implements LabelSupplier {

    final RoleRepository formRepository;

    @Override
    public String label(Object id, HttpRequest httpRequest) {
        return formRepository.findById(new RoleId((String) id))
                .map(Role::getName)
                .map(Name::name)
                .orElse("No role with id " + id);
    }
}
