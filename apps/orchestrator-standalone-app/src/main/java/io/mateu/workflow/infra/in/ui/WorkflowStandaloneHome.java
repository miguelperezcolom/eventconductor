package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.adapters.WorkflowHomeAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@UI("")
@Service
public class WorkflowStandaloneHome extends WorkflowHome {
    public WorkflowStandaloneHome(WorkflowHomeAdapter adapter) {
        super(adapter);
    }
}
