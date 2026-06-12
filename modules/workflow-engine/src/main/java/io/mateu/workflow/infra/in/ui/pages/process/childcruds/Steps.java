package io.mateu.workflow.infra.in.ui.pages.process.childcruds;

import io.mateu.core.infra.declarative.AutoListAdapter;
import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrudAdapter;
import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrudOrchestrator;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters.StepCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
@ReadOnly
public class Steps extends AutoCrudOrchestrator<Step> {

    String processId;

    public Steps withProcessId(String processId) {
        this.processId = processId;
        return this;
    }

    final StepCrudAdapter adapter;

    @Override
    public AutoCrudAdapter<Step> simpleAdapter() {
        return adapter.withProcessId(processId);
    }
}
