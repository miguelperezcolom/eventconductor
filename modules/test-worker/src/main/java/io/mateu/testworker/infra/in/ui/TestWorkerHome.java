package io.mateu.testworker.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.PageTitle;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.FieldStereotype;

@UI("/_worker")
@PageTitle("Test worker")
@Title("Test worker")
public class TestWorkerHome {

    @Menu
    TestWorkerMenu worker;

    @Stereotype(FieldStereotype.html)
    String message = """
            <p>A worker that does no work. It plays back the scenario you ask for, so a workflow
            can be driven through any outcome without anyone writing a worker for it.</p>
            <p>A process states its scenario in a <code>TEST_CONFIG</code> variable, and that
            always wins. <b>Task overrides</b> are for the processes that state nothing — they are
            how a scenario is driven by hand. <b>Received tasks</b> is what actually happened, and
            every row says which of the two answered it.</p>
            """;
}
