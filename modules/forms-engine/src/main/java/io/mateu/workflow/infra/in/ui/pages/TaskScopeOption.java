package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.domain.TaskQuery;

/** The inbox's scopes, as the filter offers them. */
public enum TaskScopeOption {
    All(TaskQuery.Scope.ALL),
    Mine(TaskQuery.Scope.MINE),
    Claimable(TaskQuery.Scope.CANDIDATE),
    MyGroups(TaskQuery.Scope.GROUPS);

    final TaskQuery.Scope scope;

    TaskScopeOption(TaskQuery.Scope scope) {
        this.scope = scope;
    }

    public TaskQuery.Scope scope() {
        return scope;
    }
}
