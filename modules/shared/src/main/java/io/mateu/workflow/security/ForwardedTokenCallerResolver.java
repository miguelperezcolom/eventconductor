package io.mateu.workflow.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The caller as read from a bearer token the request arrived with, for the deployment where
 * something in front — an API gateway, a service mesh — has already validated it. That is the shape
 * the demo stack runs in: Keycloak issues, the gateway verifies, and the engine sees a request whose
 * {@code Authorization} header carries a token nobody here checked.
 *
 * <p><b>This class does not verify anything, and that is why it is off by default.</b> It reads the
 * payload segment and believes it. Turned on where nothing validates the token first, it is not weak
 * authorization — it is none at all, because the claims are whatever the caller typed. Hence
 * {@code workflow.security.trust-forwarded-token}, which has to be set deliberately, and hence the
 * ordering in {@link CallerResolvers}: an identity this application actually authenticated always
 * wins over one it was handed.
 *
 * <p>The claim names are configurable because there is no agreement on them. The defaults are what
 * Keycloak and the OAuth2 specifications produce: {@code preferred_username} then {@code sub} for
 * who, {@code scope} (space-delimited, as RFC 8693 has it) or {@code scp} for scopes, and
 * {@code realm_access.roles} — dotted, because Keycloak nests it — then {@code roles} for roles.
 */
public class ForwardedTokenCallerResolver implements CallerResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BEARER = "Bearer ";

    private final List<String> subjectClaims;
    private final List<String> scopeClaims;
    private final List<String> roleClaims;

    public ForwardedTokenCallerResolver(List<String> subjectClaims, List<String> scopeClaims,
                                        List<String> roleClaims) {
        this.subjectClaims = subjectClaims;
        this.scopeClaims = scopeClaims;
        this.roleClaims = roleClaims;
    }

    @Override
    public AuthorizationContext current() {
        var payload = payloadOfCurrentRequest();
        if (payload == null) {
            return null;
        }
        var subject = firstString(payload, subjectClaims);
        var scopes = collect(payload, scopeClaims);
        var roles = collect(payload, roleClaims);
        if (subject == null && scopes.isEmpty() && roles.isEmpty()) {
            // A token with nothing in it we recognise is not an identity. Say so, rather than
            // manufacturing a caller who holds nothing and letting it read as "authenticated".
            return null;
        }
        return new AuthorizationContext(subject, scopes, roles);
    }

    /** The token's claims, or null for any reason at all — no request, no header, not a JWT. */
    private JsonNode payloadOfCurrentRequest() {
        try {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servlet)) {
                return null;
            }
            var header = servlet.getRequest().getHeader("Authorization");
            if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                return null;
            }
            var segments = header.substring(BEARER.length()).trim().split("\\.");
            if (segments.length < 2) {
                return null;
            }
            var json = new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
            return MAPPER.readTree(json);
        } catch (Exception e) {
            // Malformed, truncated, not base64, not JSON: all of them mean "no identity here".
            return null;
        }
    }

    private static String firstString(JsonNode payload, List<String> claims) {
        for (var claim : claims) {
            var node = at(payload, claim);
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    /**
     * Every named claim, flattened. Both shapes are accepted from every claim rather than one shape
     * per claim name: {@code scope} is a space-delimited string in the specifications and an array
     * in several issuers, and guessing wrong means silently granting nothing.
     */
    private static List<String> collect(JsonNode payload, List<String> claims) {
        var values = new ArrayList<String>();
        for (var claim : claims) {
            var node = at(payload, claim);
            if (node == null) {
                continue;
            }
            if (node.isArray()) {
                node.forEach(element -> {
                    if (element.isTextual() && !element.asText().isBlank()) {
                        values.add(element.asText().trim());
                    }
                });
            } else if (node.isTextual()) {
                for (var value : node.asText().split("[\\s,]+")) {
                    if (!value.isBlank()) {
                        values.add(value.trim());
                    }
                }
            }
        }
        return values.stream().distinct().toList();
    }

    /** A claim name, or a dotted path into a nested one ({@code realm_access.roles}). */
    private static JsonNode at(JsonNode payload, String claim) {
        JsonNode node = payload;
        for (var segment : claim.split("\\.")) {
            if (node == null) {
                return null;
            }
            node = node.get(segment);
        }
        return node;
    }
}
