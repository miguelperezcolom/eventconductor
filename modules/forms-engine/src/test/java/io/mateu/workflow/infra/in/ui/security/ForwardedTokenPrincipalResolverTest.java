package io.mateu.workflow.infra.in.ui.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.mateu.uidl.interfaces.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForwardedTokenPrincipalResolverTest {

    static ForwardedTokenPrincipalResolver resolver(boolean trust) {
        return new ForwardedTokenPrincipalResolver(trust,
                List.of("preferred_username", "sub"), List.of("scope", "scp"),
                List.of("realm_access.roles", "roles"));
    }

    static String bearer(String payload) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".";
    }

    static HttpRequest request(String authorization, Principal principal) {
        var request = mock(HttpRequest.class);
        when(request.getHeaderValue("Authorization")).thenReturn(authorization);
        when(request.getUserPrincipal()).thenReturn(principal);
        return request;
    }

    @Test
    void aTokenNobodyVerifiedIsNotBelievedUnlessTheDeploymentSaysSo() {
        var request = request(bearer("{\"preferred_username\":\"ana\"}"), null);

        assertThat(resolver(false).resolve(request)).isEmpty();
    }

    @Test
    void withTheFlagOnTheTokenNamesTheCallerAndTheirRolesAndScopes() {
        var request = request(bearer(
                "{\"preferred_username\":\"ana\",\"scope\":\"forms:read forms:write\",\"realm_access\":{\"roles\":[\"approver\"]}}"),
                null);

        var identity = resolver(true).resolve(request).orElseThrow();

        assertThat(identity.name()).isEqualTo("ana");
        assertThat(identity.roles()).containsExactly("approver");
        assertThat(identity.scopes()).containsExactly("forms:read", "forms:write");
    }

    @Test
    void anIdentityTheApplicationAuthenticatedWinsOverTheToken() {
        Principal admin = () -> "admin";
        var request = request(bearer("{\"preferred_username\":\"ana\"}"), admin);

        assertThat(resolver(true).resolve(request)).isEmpty();
    }

    @Test
    void noHeaderOrATokenNamingNobodyIsNoIdentity() {
        assertThat(resolver(true).resolve(request(null, null))).isEmpty();
        assertThat(resolver(true).resolve(request("Basic YWRtaW46eA==", null))).isEmpty();
        assertThat(resolver(true).resolve(request(bearer("{\"roles\":[\"x\"]}"), null))).isEmpty();
        assertThat(resolver(true).resolve(null)).isEmpty();
    }
}
