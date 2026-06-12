package io.mateu.workflow.infra.in.ui.pages.process.childcruds;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrudAdapter;
import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrudOrchestrator;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters.ResourceCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@Service
@RequiredArgsConstructor
@Style("width: 100%;")
@ReadOnly
public class Resources extends AutoCrudOrchestrator<Resource> {

    String processId;

    public Resources withProcessId(String processId) {
        this.processId = processId;
        return this;
    }

    final ResourceCrudAdapter adapter;


    @Override
    public AutoCrudAdapter<Resource> simpleAdapter() {
        return adapter;
    }
}
