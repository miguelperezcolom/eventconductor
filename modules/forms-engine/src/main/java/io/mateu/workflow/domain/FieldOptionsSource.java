package io.mateu.workflow.domain;

import java.util.Map;

/**
 * Where a field's choices come from when they are not written into the definition: a REST endpoint,
 * fetched by the browser as the form renders.
 *
 * <p>This is the form-definition face of mateu's {@code RestDataSource} / {@code @RestOptions} — the
 * UI talking to any REST API without the mateu backend in the middle — so a picker can offer what a
 * catalogue, a directory or a pricing service says right now, instead of what a form definition
 * committed to some months ago. The engine only carries the descriptor: it never calls the endpoint.
 *
 * <p>{@code url}, {@code headers} and {@code body} support {@code ${state.x}} interpolation against
 * the form's own values, so one field's choices can depend on another's answer, and the list
 * refetches when that answer changes.
 *
 * <p>{@code proxy} moves the fetch to the server, which resolves CORS and keeps {@code ${secret.X}}
 * credentials off the browser. Mateu resolves a proxied source from what the view declared and
 * never from anything the client sent — otherwise the proxy would be an open relay — and a task
 * form, built at runtime from this definition, declares it through {@code RestSourceSupplier} (see
 * {@code Task#declaredRestSources}). Everything a proxied fetch reads therefore comes from the
 * stored definition, which is the condition that makes it safe.
 *
 * @param url       the endpoint. Required
 * @param method    HTTP method; {@code GET} when omitted
 * @param headers   request headers, values interpolated
 * @param body      request body template for non-GET methods
 * @param itemsPath dot path to the array inside the response; blank means the response root is it
 * @param valuePath dot path within each item to the option value; {@code value} when omitted
 * @param labelPath dot path within each item to the option label; {@code label} when omitted
 * @param proxy     fetch through the server rather than from the browser: no CORS, and a
 *                  {@code ${secret.X}} placeholder in a header is resolved server-side instead of
 *                  travelling to the client. False (browser-direct) when omitted
 */
public record FieldOptionsSource(
        String url,
        String method,
        Map<String, String> headers,
        String body,
        String itemsPath,
        String valuePath,
        String labelPath,
        boolean proxy
) {

    /** The defaults are mateu's own, so a descriptor written here behaves as {@code @RestOptions}. */
    public FieldOptionsSource {
        method = method == null || method.isBlank() ? "GET" : method;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
        itemsPath = itemsPath == null ? "" : itemsPath;
        valuePath = valuePath == null || valuePath.isBlank() ? "value" : valuePath;
        labelPath = labelPath == null || labelPath.isBlank() ? "label" : labelPath;
    }

    /** A plain GET whose response root is the array of items. */
    public FieldOptionsSource(String url, String valuePath, String labelPath) {
        this(url, null, null, null, null, valuePath, labelPath, false);
    }
}
