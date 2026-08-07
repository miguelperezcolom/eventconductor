package io.mateu.workflow.application.services;

import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requires-all, fail-closed flow authorization. */
class FlowAuthorizationServiceTest {

    private final FlowAuthorizationService service = new FlowAuthorizationService();

    private AuthorizationContext caller(List<String> scopes, List<String> roles) {
        return new AuthorizationContext("alice", scopes, roles);
    }

    @Test
    void noRequirementsIsOpenToAnyone() {
        assertThat(service.authorize(caller(List.of(), List.of()), List.of(), List.of()).allowed()).isTrue();
        // ...even to an unauthenticated (null) caller — a step that declares nothing restricts nothing.
        assertThat(service.authorize(null, List.of(), null).allowed()).isTrue();
    }

    @Test
    void allowsWhenTheCallerHoldsEveryRequiredScopeAndRole() {
        var decision = service.authorize(
                caller(List.of("orders:write", "orders:read"), List.of("operator")),
                List.of("orders:write"), List.of("operator"));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.missingScopes()).isEmpty();
        assertThat(decision.missingRoles()).isEmpty();
    }

    @Test
    void deniesAndReportsExactlyWhatIsMissing() {
        var decision = service.authorize(
                caller(List.of("orders:read"), List.of()),
                List.of("orders:read", "orders:write"), List.of("approver"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.missingScopes()).containsExactly("orders:write");
        assertThat(decision.missingRoles()).containsExactly("approver");
    }

    @Test
    void requiresAllNotAnyScope() {
        var decision = service.authorize(caller(List.of("a"), List.of()), List.of("a", "b"), List.of());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.missingScopes()).containsExactly("b");
    }

    @Test
    void aMissingCallerIsDeniedTheMomentAnythingIsRequired() {
        var decision = service.authorize(null, List.of("orders:write"), List.of());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.missingScopes()).containsExactly("orders:write");
    }

    @Test
    void theSystemIdentityHoldsNothingSoItCannotRunScopedSteps() {
        assertThat(service.authorize(AuthorizationContext.SYSTEM, List.of("orders:write"), List.of()).allowed())
                .isFalse();
        // ...but it runs anything unrestricted, like any caller.
        assertThat(service.authorize(AuthorizationContext.SYSTEM, List.of(), List.of()).allowed()).isTrue();
    }
}
