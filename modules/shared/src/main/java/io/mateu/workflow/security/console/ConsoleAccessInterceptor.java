package io.mateu.workflow.security.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Applies {@link ConsoleAccess} to the consoles. It decides on the <em>handler</em> Spring MVC
 * resolved for the request, not on its path: whatever spelling of the URL reaches a console —
 * encoded, with {@code ;parameters}, with a trailing slash, or a Mateu UI mounted at the
 * application's own root — reaches the same handler and is judged the same. Nothing else (the
 * application's own controllers, the actuator, static resources) is touched, and an application
 * without a console (a worker with Mateu pages of its own) keeps its UIs as they were.
 */
public class ConsoleAccessInterceptor implements HandlerInterceptor {

    /** The consoles the EventConductor modules contribute, and their downloads. */
    static final String CONSOLE_PACKAGE = "io.mateu.workflow.infra.in.ui.";
    static final Set<String> GUARDED_TYPES = Set.of("io.mateu.workflow.infra.in.rest.RuleReadController");
    /** Mateu's description of every UI in the application. */
    static final String MATEU_BUNDLE = "io.mateu.MateuBundleController";
    static final String MATEU_MCP = "io.mateu.mcp.";
    /** Every Mateu UI's action endpoint implements it; the engine's pages can be mounted in any of them. */
    static final String MATEU_CONTROLLER = "io.mateu.core.infra.MateuController";
    static final String MATEU_UI = "io.mateu.uidl.annotations.UI";
    /** MCP servers: Mateu's own (it drives the UIs) and the MCP SDK / Spring AI transports. */
    static final List<String> MCP_PACKAGES = List.of(
            MATEU_MCP, "io.modelcontextprotocol.", "org.springframework.ai.mcp.");

    private final ConsoleAccess access;

    public ConsoleAccessInterceptor(ConsoleAccess access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!isGuarded(handler) || access.allows()) {
            return true;
        }
        refuse(request, response);
        return false;
    }

    /**
     * Whether the handler is a console: always the EventConductor consoles' own controllers and the
     * rule-read endpoints; any Mateu UI or Mateu's MCP when the application mounts a console; an MCP
     * server when it exposes the engine's tools.
     */
    boolean isGuarded(Object handler) {
        if (handler == null) {
            return false;
        }
        var type = handler instanceof HandlerMethod method
                ? ClassUtils.getUserClass(method.getBeanType()) : handler.getClass();
        var name = type.getName();
        if (name.startsWith(CONSOLE_PACKAGE) || GUARDED_TYPES.contains(name)) {
            return true;
        }
        if (isMcp(name)) {
            return name.startsWith(MATEU_MCP) ? access.consolesPresent()
                    : access.consolesPresent() || access.engineToolsPresent();
        }
        return handler instanceof HandlerMethod && isMateuUi(type) && access.consolesPresent();
    }

    static boolean isMateuUi(Class<?> type) {
        if (MATEU_BUNDLE.equals(type.getName())) {
            return true;
        }
        for (var implemented : ClassUtils.getAllInterfacesForClassAsSet(type)) {
            if (MATEU_CONTROLLER.equals(implemented.getName())) {
                return true;
            }
        }
        return isUiIndexController(type);
    }

    private static boolean isMcp(String className) {
        return MCP_PACKAGES.stream().anyMatch(className::startsWith);
    }

    /** The page Mateu's annotation processor generates for a {@code @UI} class X, as {@code XController}. */
    private static boolean isUiIndexController(Class<?> type) {
        var name = type.getName();
        if (!name.endsWith("Controller")) {
            return false;
        }
        try {
            var ui = ClassUtils.forName(name.substring(0, name.length() - "Controller".length()), type.getClassLoader());
            for (Annotation annotation : ui.getAnnotations()) {
                if (MATEU_UI.equals(annotation.annotationType().getName())) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException | LinkageError notAUi) {
            return false;
        }
    }

    private static void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE)) {
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.getWriter().write("<!doctype html><html><head><meta charset=\"utf-8\"><title>Forbidden</title>"
                    + "</head><body><h1>403 Forbidden</h1><p>" + HtmlUtils.htmlEscape(ConsoleAccess.REFUSED)
                    + "</p></body></html>");
        } else {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\""
                    + ConsoleAccess.REFUSED.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        }
    }
}
