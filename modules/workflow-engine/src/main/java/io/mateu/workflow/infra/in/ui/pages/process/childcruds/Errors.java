package io.mateu.workflow.infra.in.ui.pages.process.childcruds;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters.ErrorCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
@ReadOnly
public class Errors extends AutoCrud<Error> {

    String processId;

    public Errors withProcessId(String processId) {
        this.processId = processId;
        return this;
    }

    final ErrorCrudAdapter adapter;

    @Override
    public CrudRepository<Error> store() {
        return adapter.withProcessId(processId).repository();
    }
}
