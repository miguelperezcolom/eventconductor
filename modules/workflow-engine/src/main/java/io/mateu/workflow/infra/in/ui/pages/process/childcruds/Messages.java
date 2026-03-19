package io.mateu.workflow.infra.in.ui.pages.process.childcruds;

import io.mateu.core.infra.declarative.AutoListAdapter;
import io.mateu.core.infra.declarative.AutoListOrchestrator;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Style;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.adapters.MessageCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Style("width: 100%;")
@ReadOnly
public class Messages extends AutoListOrchestrator<Message> {

    String processId;

    public Messages withProcessId(String processId) {
        this.processId = processId;
        return this;
    }

    final MessageCrudAdapter adapter;

    @Override
    public AutoListAdapter<Message> simpleListAdapter() {
        return adapter.withProcessId(processId);
    }
}
