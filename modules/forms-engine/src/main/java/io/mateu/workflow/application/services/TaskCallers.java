package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@link TaskCaller} on the other end of the current request: who they are and their groups from
 * the engine's {@link CallerResolver} (so the same {@code workflow.security.claims.*} and
 * {@code trust-forwarded-token} settings apply), and whether they are a task admin — they hold one of
 * {@code forms.tasks.admin-roles} (default {@code task-admin}).
 */
@Service
public class TaskCallers {

    final CallerResolver callerResolver;
    final Set<String> adminRoles;

    public TaskCallers(CallerResolver callerResolver,
                       @Value("${forms.tasks.admin-roles:task-admin}") List<String> adminRoles) {
        this.callerResolver = callerResolver;
        this.adminRoles = adminRoles == null ? Set.of() : adminRoles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The caller of the current request; {@link TaskCaller#anonymous()} when there is none. */
    public TaskCaller current() {
        return forUser(null);
    }

    /**
     * The caller of the current request, known by {@code username} — the name the UI read off the
     * request — when it has one; the resolver's subject otherwise.
     */
    public TaskCaller forUser(String username) {
        AuthorizationContext context;
        try {
            context = callerResolver.current();
        } catch (RuntimeException e) {
            context = null;
        }
        var subject = username != null && !username.isBlank() ? username
                : context == null ? null : context.subject();
        var roles = context == null ? List.<String>of() : context.roles();
        var admin = roles.stream().anyMatch(adminRoles::contains);
        return TaskCaller.of(subject, roles, admin);
    }
}
