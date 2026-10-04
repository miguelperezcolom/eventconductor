package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.data.RouteEntry;
import io.mateu.uidl.interfaces.RouteEntrySupplier;
import io.mateu.workflow.infra.in.ui.pages.Task;
import io.mateu.workflow.infra.in.ui.pages.Tasks;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The forms UI's routes that are not a menu entry of one particular front door.
 *
 * <p>A task's own page, {@code /forms/task/{id}}, is where every link to one task lands — the
 * tasks list's Run, and a host's inbox — and it is not on any menu. It used to be declared on the
 * page as {@code @Route}; when Mateu folded {@code @Route} into {@code @UI}, the page became an app
 * mounted at the literal path {@code /forms/task/:_taskId}, and the route inside {@code /_forms} was
 * answered "Not found." Mateu now keeps inner routes in its route registry, which this supplies.
 *
 * <p>The tasks list is here too, although {@link FormsOperationsMenu} already carries it, so that
 * it answers through either front door of this pod. A console that mounts only {@link
 * FormsAdminHome} still has people who answer tasks — the task page's "Back to list" and the
 * navigation after completing one lead there — and a route that resolves only through one of the
 * two menus is a link that works or says "Not found." depending on which console followed it.
 *
 * <p>Built from the classes this jar holds, never from a request: an entry is an allow-list line.
 */
@Component
public class FormsRoutes implements RouteEntrySupplier {

    /** The task page's route. {@code _taskId} is the field of {@link Task} the id is written to. */
    public static final String TASK = "/forms/task/:_taskId";

    /** The tasks list's route: the same one its menu entry has always had. */
    public static final String TASKS = "/forms/tasks";

    /** The route of one task's page. */
    public static String task(String taskId) {
        return TASK.replace(":_taskId", taskId);
    }

    @Override
    public List<RouteEntry> routes() {
        return List.of(
                RouteEntry.of(TASK, Task.class.getName()),
                RouteEntry.of(TASKS, Tasks.class.getName()));
    }
}
