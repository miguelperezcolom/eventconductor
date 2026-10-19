package io.mateu.workflow.domain;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who is asking for, or acting on, a human task: their user name, the groups they are in, and whether
 * they are a task admin.
 *
 * <p>A group is one of the caller's roles — the same roles {@code workflow.security.claims.roles}
 * reads from a token, or the {@code ROLE_} authorities of a login — so mapping an identity provider's
 * {@code groups} claim is a matter of listing it there. Group names are compared without a leading
 * {@code /}: Keycloak writes group paths ({@code /finance}), definitions write names ({@code finance}).
 *
 * @param subject the user name; null for nobody (an anonymous request)
 * @param groups  the groups (roles) they are in
 * @param admin   whether they hold the task-admin role ({@code forms.tasks.admin-roles}): an admin sees
 *                and may claim or reassign every task, whoever it names — but still only on forms
 *                whose {@code requiredRoles}/{@code requiredScopes} they hold
 */
public record TaskCaller(String subject, Set<String> groups, boolean admin) {

    public TaskCaller {
        subject = subject == null || subject.isBlank() ? null : subject;
        groups = groups == null ? Set.of() : groups.stream()
                .filter(Objects::nonNull)
                .map(TaskCaller::groupName)
                .filter(group -> !group.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Nobody: sees only the tasks that name nobody. */
    public static TaskCaller anonymous() {
        return new TaskCaller(null, Set.of(), false);
    }

    /** A user with these groups. */
    public static TaskCaller of(String subject, Collection<String> groups, boolean admin) {
        return new TaskCaller(subject, groups == null ? Set.of() : new java.util.HashSet<>(groups), admin);
    }

    /** Whether this is somebody. */
    public boolean known() {
        return subject != null;
    }

    /** Whether they are in any of {@code candidateGroups}. */
    public boolean inAnyOf(Collection<String> candidateGroups) {
        return candidateGroups != null && candidateGroups.stream()
                .filter(Objects::nonNull)
                .map(TaskCaller::groupName)
                .anyMatch(groups::contains);
    }

    /** A group's name as compared: trimmed, without a leading {@code /}. */
    public static String groupName(String group) {
        var name = group.trim();
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        return name;
    }
}
