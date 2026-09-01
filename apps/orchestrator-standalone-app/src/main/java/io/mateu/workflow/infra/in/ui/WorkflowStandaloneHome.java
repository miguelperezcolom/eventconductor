package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.adapters.WorkflowHomeAdapter;
import org.springframework.stereotype.Service;


/**
 * The orchestrator running on its own, with no shell in front of it: everything at the root path.
 *
 * <p>It inherits {@code WorkflowHome}'s dashboard and its operations menu, and adds the
 * administration one beside it — a standalone deployment has one console, so splitting the UI in
 * two would only hide half of it. The two arrive as two menu groups rather than one, which is the
 * honest rendering of what the engine now offers.
 */
@UI("")
@Service
public class WorkflowStandaloneHome extends WorkflowHome {
    public WorkflowStandaloneHome(WorkflowHomeAdapter adapter) {
        super(adapter);
    }

    // Routes under this group are /administration/*, not /workflow/*, because Mateu derives them
    // from the field name and the inherited "workflow" field already holds the operations half.
    // Only standalone mode is affected: behind a shell the two halves are mounted from different
    // base URLs and both keep their /workflow/* routes.
    @Menu
    WorkflowAdminMenu administration;
}
