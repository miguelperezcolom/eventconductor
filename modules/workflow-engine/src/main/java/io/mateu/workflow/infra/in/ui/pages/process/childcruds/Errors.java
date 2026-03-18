package io.mateu.workflow.infra.in.ui.pages.process.childcruds;

import io.mateu.core.infra.declarative.AutoListAdapter;
import io.mateu.core.infra.declarative.AutoListOrchestrator;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters.ErrorCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
@ReadOnly
public class Errors extends AutoListOrchestrator<Error> {

    String processId;

    public Errors withProcessId(String processId) {
        this.processId = processId;
        return this;
    }

    final ErrorCrudAdapter adapter;

    @Override
    public AutoListAdapter<Error> simpleListAdapter() {
        return adapter.withProcessId(processId);
    }
}
