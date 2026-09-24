package io.mateu.workflow.domain.aggregates;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an {@code HTTP_CALL} step calls. Carried in the {@code .ec} file as the step's {@code http}
 * block. Every string that is a template ({@code url}, {@code path}, query and header values, the
 * body) is rendered by the engine from the process state before the task is dispatched; the call itself
 * is made by the built-in {@code http-call} worker task.
 *
 * @param connection    a named connection ({@code workflow.http.connections.<name>}): base URL, auth, timeouts
 * @param method        GET, POST, PUT, PATCH, DELETE or HEAD
 * @param url           an absolute URL (template) — instead of {@code connection}; subject to the host allowlist
 * @param path          with a connection: the path (template) under its base URL
 * @param query         query parameters (values are templates)
 * @param headers       request headers (values are templates; never secrets)
 * @param body          a structured body template (sent as JSON)
 * @param bodyTemplate  a text body template (sent as is; set {@code Content-Type} in {@code headers})
 * @param successStatus the statuses that complete the step; default any 2xx
 * @param output        process variable → JEXL over {@code {status, headers, body}} (body parsed when JSON)
 * @param auth          a named auth profile ({@code workflow.http.auth.<name>}), or an inline block whose
 *                      credentials are {@code ${secret:NAME}} references only
 * @param retryOn       which failures the step's {@code retries} apply to: {@code 5xx}, {@code 4xx},
 *                      {@code io}, or specific codes; default {@code [5xx, io]}
 */
public record HttpCall(
        String connection,
        String method,
        String url,
        String path,
        Map<String, String> query,
        Map<String, String> headers,
        Object body,
        String bodyTemplate,
        List<Integer> successStatus,
        Map<String, String> output,
        Object auth,
        List<String> retryOn
) {

    public static final Set<String> METHODS = io.mateu.workflow.analysis.HttpCallRules.METHODS;
}
