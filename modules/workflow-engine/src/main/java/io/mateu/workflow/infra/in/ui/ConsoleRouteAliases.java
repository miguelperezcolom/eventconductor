package io.mateu.workflow.infra.in.ui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Sends a browser that asks for a console page by its kebab-case name to the page itself:
 * {@code /workflow/batch-operations} to {@code /workflow/batchOperations}.
 *
 * <p>Mateu names a menu entry's route after its Java field, so the console's multi-word pages live at
 * camelCase paths — {@code batchOperations}, {@code eventTriggers}, {@code receivedEvents} — while
 * everything else an operator reads spells them in kebab case: the REST API
 * ({@code /workflow/api/batch-operations}), the docs, the MCP tools. Typing the one into the address bar
 * by analogy with the other answered 404. The camelCase routes stay what they are (bookmarks and links
 * hold them); this only redirects the spelling people reach for.
 *
 * <p>Narrow on purpose: a {@code GET} for a page ({@code Accept: text/html}), one of these segments, and
 * never under {@code /api/} or Mateu's own {@code /mateu/} endpoints, so no API call is ever rewritten.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Component
public class ConsoleRouteAliases extends OncePerRequestFilter {

    /** Kebab-case segment → the console route segment it names. */
    static final Map<String, String> ALIASES = Map.of(
            "batch-operations", "batchOperations",
            "event-triggers", "eventTriggers",
            "received-events", "receivedEvents");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return true;
        }
        var accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || !accept.contains(MediaType.TEXT_HTML_VALUE)) {
            return true;
        }
        var path = pathOf(request);
        return path.contains("/api/") || path.startsWith("/mateu/") || aliased(path) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var target = aliased(pathOf(request));
        if (target == null) {
            chain.doFilter(request, response);
            return;
        }
        var query = request.getQueryString();
        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader(HttpHeaders.LOCATION, request.getContextPath() + target + (query == null ? "" : "?" + query));
    }

    /** The path with every aliased segment replaced, or null when none is. */
    static String aliased(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        var segments = path.split("/", -1);
        var changed = false;
        for (int i = 0; i < segments.length; i++) {
            var route = ALIASES.get(segments[i]);
            if (route != null) {
                segments[i] = route;
                changed = true;
            }
        }
        return changed ? String.join("/", segments) : null;
    }

    private static String pathOf(HttpServletRequest request) {
        var uri = request.getRequestURI();
        var context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }
}
