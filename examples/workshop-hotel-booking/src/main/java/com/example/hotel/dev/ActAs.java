package com.example.hotel.dev;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * WORKSHOP / LAPTOP ONLY — a user switch for the console, with no login server.
 *
 * <p>{@code GET /dev/act-as/manager} makes this browser "mia", of the {@code front-desk-managers}
 * group, and opens the task inbox; {@code GET /dev/act-as/nobody} goes back to anonymous. It does
 * so by leaving an <b>unsigned</b> token where the console's frontend (Mateu) keeps its sign-in
 * token — the {@code __mateu_auth_token} cookie — so every request the console makes carries it as
 * {@code Authorization: Bearer …}. The app believes it only because the workshop sets
 * {@code workflow.security.trust-forwarded-token=true}: anybody can type such a token, which is why
 * that setting, and this class, have no place in anything reachable from a network.
 *
 * <p>Two guards, so it cannot quietly become more than that: it answers only while
 * {@code trust-forwarded-token} is on, and only to a browser on this machine (a loopback address).
 */
@RestController
public class ActAs {

    /** Who the switch can make you: the people of the workshop. */
    private static final Map<String, Map<String, Object>> PEOPLE = Map.of(
            "manager", Map.of(
                    "sub", "mia",
                    "preferred_username", "mia",
                    "realm_access", Map.of("roles", java.util.List.of("front-desk-managers"))));

    /** Mateu's frontend reads the sign-in token from this cookie and sends it on every request. */
    static final String MATEU_TOKEN_COOKIE = "__mateu_auth_token";

    private final boolean trustForwardedToken;

    public ActAs(@Value("${workflow.security.trust-forwarded-token:false}") boolean trustForwardedToken) {
        this.trustForwardedToken = trustForwardedToken;
    }

    @GetMapping("/dev/act-as/{who}")
    public ResponseEntity<String> actAs(@PathVariable String who, HttpServletRequest request) {
        if (!trustForwardedToken || !fromThisMachine(request)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        ResponseCookie cookie;
        if ("nobody".equals(who)) {
            cookie = ResponseCookie.from(MATEU_TOKEN_COOKIE, "").path("/").maxAge(0).sameSite("Strict").build();
        } else if (PEOPLE.containsKey(who)) {
            cookie = ResponseCookie.from(MATEU_TOKEN_COOKIE, unsignedToken(PEOPLE.get(who)))
                    .path("/").sameSite("Strict").build();   // not HttpOnly: Mateu's frontend reads it
        } else {
            return ResponseEntity.badRequest().contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .body("Unknown person: use /dev/act-as/manager or /dev/act-as/nobody.\n");
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .location(URI.create("nobody".equals(who) ? "/" : "/forms/tasks"))
                .build();
    }

    static boolean fromThisMachine(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** A JWT with no signature ({@code alg: none}): exactly what scripts/approve.sh sends. */
    static String unsignedToken(Map<String, Object> claims) {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            return b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + b64(json.writeValueAsString(claims)) + ".";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
