package io.mateu.workflow.application.services;

import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The caller of a task request: the engine's identity, its roles as groups, and the task-admin role. */
class TaskCallersTest {

    CallerResolver resolver = mock(CallerResolver.class);

    @Test
    void theCallersRolesAreTheirGroupsAndTheAdminRoleMakesThemAnAdmin() {
        when(resolver.current()).thenReturn(new AuthorizationContext("carol", List.of("tasks:read"),
                List.of("finance", "task-admin")));

        var caller = new TaskCallers(resolver, List.of("task-admin")).current();

        assertThat(caller.subject()).isEqualTo("carol");
        assertThat(caller.groups()).containsExactlyInAnyOrder("finance", "task-admin");
        assertThat(caller.admin()).isTrue();
    }

    @Test
    void theNameTheUiReadIsWhoTheyAre() {
        when(resolver.current()).thenReturn(new AuthorizationContext("sub-123", List.of(), List.of("finance")));

        var caller = new TaskCallers(resolver, Arrays.asList(" ", null, "boss")).forUser("carol");

        assertThat(caller.subject()).isEqualTo("carol");
        assertThat(caller.groups()).containsExactly("finance");
        assertThat(caller.admin()).isFalse();
    }

    @Test
    void nobodyOnTheRequestIsAnonymous() {
        when(resolver.current()).thenReturn(null);

        assertThat(new TaskCallers(resolver, null).current().known()).isFalse();
        assertThat(new TaskCallers(resolver, null).forUser(" ").known()).isFalse();
    }

    @Test
    void aResolverThatFailsIsNobodyRatherThanAnError() {
        when(resolver.current()).thenThrow(new IllegalStateException("no request"));

        assertThat(new TaskCallers(resolver, List.of()).forUser("alice").subject()).isEqualTo("alice");
    }
}
