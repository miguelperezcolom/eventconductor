package io.mateu.testworker.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.testworker.infra.in.ui.pages.ReceivedTasks;
import io.mateu.testworker.infra.in.ui.pages.TaskOverrides;

public class TestWorkerMenu {

    @Menu
    ReceivedTasks receivedTasks;

    @Menu
    TaskOverrides taskOverrides;
}
