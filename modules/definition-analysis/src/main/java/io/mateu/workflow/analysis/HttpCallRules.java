package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The structural rules of an {@code HTTP_CALL} step's {@code http} block, on its generic (parsed
 * JSON/YAML) form — shared, as code, by the engine and the Maven plugin. The template expressions in
 * it are checked separately, by whoever holds a JEXL engine.
 */
public final class HttpCallRules {

    public static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");
    public static final Set<String> CREDENTIAL_FIELDS = Set.of("token", "password", "value", "clientSecret", "client-secret");
    public static final Pattern SECRET_REFERENCE = Pattern.compile("^\\$\\{secret:[A-Za-z0-9_.-]+}$");
    private static final Pattern RETRY_ON = Pattern.compile("4xx|5xx|io|[1-5][0-9][0-9]");

    /** Problems with an http block; {@code http} is the block as a map (null when absent). */
    public static List<String> problems(Map<?, ?> http, String where) {
        var problems = new ArrayList<String>();
        if (http == null) {
            problems.add(where + " must define an http block.");
            return problems;
        }
        var method = http.get("method") == null ? "GET" : String.valueOf(http.get("method")).toUpperCase();
        if (!METHODS.contains(method)) {
            problems.add(where + " method '" + http.get("method") + "' is not one of " + METHODS + ".");
        }
        boolean hasConnection = set(http.get("connection"));
        boolean hasUrl = set(http.get("url"));
        if (hasConnection == hasUrl) {
            problems.add(where + " must define exactly one of connection and url.");
        }
        if (hasUrl && set(http.get("path"))) {
            problems.add(where + " path is only for a connection; put it in the url.");
        }
        if (http.get("body") != null && http.get("bodyTemplate") != null) {
            problems.add(where + " declares both body and bodyTemplate.");
        }
        if (http.get("retryOn") instanceof Collection<?> retryOn) {
            for (var retry : retryOn) {
                if (!RETRY_ON.matcher(String.valueOf(retry)).matches()) {
                    problems.add(where + " retryOn value '" + retry + "' is not 4xx, 5xx, io or a status code.");
                }
            }
        }
        for (var field : List.of("url", "path", "query", "headers", "body", "bodyTemplate")) {
            var value = http.get(field);
            if (value != null && String.valueOf(value).contains("${secret:")) {
                problems.add(where + ": ${secret:…} is only allowed in an inline auth block's credentials (found in " + field + ").");
            }
        }
        var auth = http.get("auth");
        if (auth instanceof Map<?, ?> inline) {
            inline.forEach((key, value) -> {
                if (CREDENTIAL_FIELDS.contains(String.valueOf(key))
                        && (value == null || !SECRET_REFERENCE.matcher(String.valueOf(value)).matches())) {
                    problems.add(where + " auth." + key + " must be a ${secret:NAME} reference — never a literal"
                            + " credential in a definition.");
                }
            });
        } else if (auth != null && !(auth instanceof String)) {
            problems.add(where + " auth must be a profile name or an inline block.");
        }
        return problems;
    }

    private static boolean set(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private HttpCallRules() {
    }
}
