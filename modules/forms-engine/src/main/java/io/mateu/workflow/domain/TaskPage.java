package io.mateu.workflow.domain;

import java.util.List;

/** One page of an inbox: the tasks on it, and how many there are in all. */
public record TaskPage(List<FormExecution> items, long total, int page, int size) {

    public TaskPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static TaskPage empty(TaskQuery query) {
        return new TaskPage(List.of(), 0, query.page(), query.size());
    }
}
