package io.mateu.workflow.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTHZ-WHO-01..09 — where the engine gets an identity from, and what it does when it cannot.
 *
 * <p>Two deployments, two answers: an application that authenticated the caller itself, and one
 * behind a gateway that validated a token and forwarded it. The tests that matter most are the ones
 * about absence — no request, no header, a token that is not one — because every one of those has to
 * come back as "nobody", which is what makes a fail-closed check fail closed.
 */
class CallerResolutionTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ authenticated here

    private void authenticatedAs(String name, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "n/a", List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /**
     * AUTHZ-WHO-01. Spring's two prefixes read as what they mean, so a deployment's existing
     * authorities work without being restated — including the {@code ROLE_ADMIN} the standalone
     * apps' HTTP Basic user already has.
     */
    @Test
    void springAuthoritiesBecomeRolesAndScopes() {
        authenticatedAs("ana", "ROLE_operator", "SCOPE_payments:write", "auditor");

        var caller = new SpringSecurityCallerResolver().current();

        assertThat(caller.subject()).isEqualTo("ana");
        assertThat(caller.scopes()).containsExactly("payments:write");
        assertThat(caller.roles()).containsExactlyInAnyOrder("operator", "auditor");
    }

    /** AUTHZ-WHO-02. Not logged in is nobody — and so is Spring's anonymous stand-in for it. */
    @Test
    void anEmptyOrAnonymousContextIsNobody() {
        assertThat(new SpringSecurityCallerResolver().current()).isNull();

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(new SpringSecurityCallerResolver().current()).isNull();
    }

    // ------------------------------------------------------------------ validated elsewhere

    private static ForwardedTokenCallerResolver forwarded() {
        return new ForwardedTokenCallerResolver(
                List.of("preferred_username", "sub"), List.of("scope", "scp"),
                List.of("realm_access.roles", "roles"));
    }

    private static void requestCarrying(String authorizationHeader) {
        var request = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static String tokenWith(String payloadJson) {
        var payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return "Bearer header." + payload + ".signature";
    }

    /** AUTHZ-WHO-03. The shape Keycloak issues: roles nested under realm_access, scopes space-delimited. */
    @Test
    void keycloaksOwnClaimShapesAreRead() {
        requestCarrying(tokenWith("""
                {"preferred_username":"ana","sub":"uuid-1",
                 "scope":"openid payments:write",
                 "realm_access":{"roles":["operator","auditor"]}}"""));

        var caller = forwarded().current();

        assertThat(caller.subject()).isEqualTo("ana");
        assertThat(caller.scopes()).containsExactlyInAnyOrder("openid", "payments:write");
        assertThat(caller.roles()).containsExactlyInAnyOrder("operator", "auditor");
    }

    /**
     * AUTHZ-WHO-04. A claim is accepted as a delimited string or as an array, from either name.
     * Issuers disagree about which, and guessing wrong grants nothing while looking like it worked.
     */
    @Test
    void aClaimIsReadWhicheverOfItsTwoShapesItArrivesIn() {
        requestCarrying(tokenWith("""
                {"sub":"svc-1","scp":["a","b"],"roles":"reader,writer"}"""));

        var caller = forwarded().current();

        assertThat(caller.subject()).isEqualTo("svc-1");
        assertThat(caller.scopes()).containsExactlyInAnyOrder("a", "b");
        assertThat(caller.roles()).containsExactlyInAnyOrder("reader", "writer");
    }

    /** AUTHZ-WHO-05. No request at all — a Kafka consumer, a scheduler — is nobody, not a crash. */
    @Test
    void withNoRequestInProgressThereIsNobody() {
        assertThat(forwarded().current()).isNull();
    }

    /** AUTHZ-WHO-06. Nor is a request without the header, or with one that is not a bearer token. */
    @Test
    void aRequestWithNothingToIdentifyAnybodyIsNobody() {
        requestCarrying(null);
        assertThat(forwarded().current()).isNull();

        // A scheme this resolver has nothing to say about. Deliberately not a real base64
        // credential: a secret scanner cannot tell a test fixture from a leak, and it is right not to.
        requestCarrying("Basic " + "not-a-bearer-token");
        assertThat(forwarded().current()).isNull();
    }

    /**
     * AUTHZ-WHO-07. A token that is truncated, not base64, or not JSON is nobody. It must not throw:
     * a malformed credential is a request that gets refused by a fail-closed check with a reason, not
     * one that fails with a stack trace.
     */
    @Test
    void aTokenThatIsNotOneIsNobodyRatherThanAnError() {
        for (var header : List.of("Bearer nonsense", "Bearer a.b", "Bearer a.!!!.c",
                "Bearer header." + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("not json".getBytes(StandardCharsets.UTF_8)) + ".sig")) {
            requestCarrying(header);
            assertThat(forwarded().current()).describedAs(header).isNull();
        }
    }

    /** AUTHZ-WHO-08. A valid token carrying no claim we recognise is nobody, not an empty somebody. */
    @Test
    void aTokenWithNoRecognisedClaimIsNobody() {
        requestCarrying(tokenWith("{\"iss\":\"someone\",\"exp\":1}"));

        assertThat(forwarded().current()).isNull();
    }

    // ------------------------------------------------------------------ order

    /**
     * AUTHZ-WHO-09. Verified beats asserted. A request carrying both a login this application
     * performed and a token it was handed is answered by the login — so turning on
     * {@code trust-forwarded-token} for the gateway case cannot quietly override a real one.
     */
    @Test
    void anIdentityThisApplicationAuthenticatedWinsOverOneItWasHanded() {
        authenticatedAs("ana", "ROLE_operator");
        requestCarrying(tokenWith("{\"sub\":\"someone-else\",\"roles\":[\"admin\"]}"));

        var caller = new CallerResolvers(List.of(new SpringSecurityCallerResolver(), forwarded()))
                .current();

        assertThat(caller.subject()).isEqualTo("ana");
        assertThat(caller.roles()).containsExactly("operator");
    }

    /** …and with nothing configured at all, the composite is silent rather than inventive. */
    @Test
    void withNoSourceAtAllThereIsNobody() {
        assertThat(new CallerResolvers(List.of()).current()).isNull();
    }
}
