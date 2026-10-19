package io.mateu.workflow.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * What a task inbox asks for. Every filter is optional; what the caller may see is always applied on
 * top (see {@code TaskVisibility}).
 *
 * @param scope       {@link Scope#ALL} (default): every task the caller may see; {@link Scope#MINE}:
 *                    assigned to them; {@link Scope#CANDIDATE}: unassigned and offered to them, by
 *                    name or through a group; {@link Scope#GROUPS}: unassigned and offered to one of
 *                    their groups
 * @param statuses    the statuses to list; empty means the open ones (PENDING, ASSIGNED)
 * @param minPriority only tasks at least this urgent
 * @param overdue     true: only overdue tasks; false: only tasks that are not; null: either
 * @param dueBefore   only tasks due before this moment (UTC)
 * @param processId   only the tasks of this process
 * @param businessKey only the tasks of processes with this business key
 * @param formId      only the tasks of this form
 * @param sort        {@link Sort#PRIORITY} (default: most urgent first, then soonest due),
 *                    {@link Sort#DUE} (soonest due first, then most urgent), {@link Sort#CREATED}
 *                    (newest first)
 * @param page        0-based page
 * @param size        page size, 1 to {@value #MAX_SIZE}
 */
public record TaskQuery(Scope scope,
                        List<FormExecutionStatus> statuses,
                        Integer minPriority,
                        Boolean overdue,
                        LocalDateTime dueBefore,
                        String processId,
                        String businessKey,
                        String formId,
                        Sort sort,
                        int page,
                        int size) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 500;

    public enum Scope {
        ALL, MINE, CANDIDATE, GROUPS;

        /** Read from a request parameter; null and blank are {@link #ALL}. */
        public static Scope parse(String text) {
            if (text == null || text.isBlank()) {
                return ALL;
            }
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "mine" -> MINE;
                case "candidate", "claimable" -> CANDIDATE;
                case "groups", "my-groups", "mygroups" -> GROUPS;
                default -> throw new IllegalArgumentException("scope must be all, mine, candidate or groups, not '" + text + "'");
            };
        }
    }

    public enum Sort {
        PRIORITY, DUE, CREATED;

        /** Read from a request parameter; null and blank are {@link #PRIORITY}. */
        public static Sort parse(String text) {
            if (text == null || text.isBlank()) {
                return PRIORITY;
            }
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "priority" -> PRIORITY;
                case "due", "duedate" -> DUE;
                case "created", "createdat" -> CREATED;
                default -> throw new IllegalArgumentException("sort must be priority, due or created, not '" + text + "'");
            };
        }
    }

    public TaskQuery {
        scope = scope == null ? Scope.ALL : scope;
        statuses = statuses == null || statuses.isEmpty()
                ? List.of(FormExecutionStatus.PENDING, FormExecutionStatus.ASSIGNED)
                : List.copyOf(statuses);
        sort = sort == null ? Sort.PRIORITY : sort;
        page = Math.max(0, page);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        processId = blankToNull(processId);
        businessKey = blankToNull(businessKey);
        formId = blankToNull(formId);
    }

    /** Every open task the caller may see, most urgent first. */
    public static TaskQuery open() {
        return new TaskQuery(Scope.ALL, null, null, null, null, null, null, null, Sort.PRIORITY, 0, DEFAULT_SIZE);
    }

    public TaskQuery withScope(Scope scope) {
        return new TaskQuery(scope, statuses, minPriority, overdue, dueBefore, processId, businessKey, formId, sort, page, size);
    }

    public TaskQuery withStatuses(List<FormExecutionStatus> statuses) {
        return new TaskQuery(scope, statuses, minPriority, overdue, dueBefore, processId, businessKey, formId, sort, page, size);
    }

    public TaskQuery withPage(int page, int size) {
        return new TaskQuery(scope, statuses, minPriority, overdue, dueBefore, processId, businessKey, formId, sort, page, size);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
