package io.mateu.workflow.security.console;

import io.mateu.workflow.mcp.McpTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Who may use the consoles an EventConductor application serves besides its REST API: the admin UI
 * (every Mateu UI in the application — the engine's pages can be mounted in any of them), the MCP
 * endpoints, the rule-read endpoints ({@code GET /rules}, {@code GET /rules/{id}}) and the
 * task-project downloads ({@code /eventconductor/tasks/{group}/*.zip}).
 *
 * <p>The rule is the engine API's, minus the api key — a browser cannot send one:
 *
 * <ul>
 *   <li>a Spring Security filter chain protects the application: the request has passed the
 *       application's own authentication, so it comes in;</li>
 *   <li>no filter chain: refused with 403, unless {@code workflow.message-api.allow-anonymous} is
 *       true. Since 2.28.0 — before, such an application answered anybody who could reach the port.</li>
 * </ul>
 *
 * <p>Any filter chain counts, including one that permits everything — the same as for the API.
 */
public class ConsoleAccess implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAccess.class);

    public static final String ALLOW_ANONYMOUS = "workflow.message-api.allow-anonymous";
    public static final String UI_ENABLED = "workflow.ui.enabled";
    public static final String SECURITY_FILTER_CHAIN = "org.springframework.security.web.SecurityFilterChain";
    /** The engine's own check, which states this rule in its startup line when it is present. */
    static final String ENGINE_API_ACCESS = "io.mateu.workflow.infra.in.rest.EngineApiAccess";

    /** What the consoles are, for the log and the refusal. */
    public static final String CONSOLES = "the admin UI (every Mateu UI in this application: /_workflow,"
            + " /_workflow-admin, /_rules, /_forms…), the MCP endpoints, GET /rules and the task-project zips";

    public static final String REFUSED = "This EventConductor console refuses anonymous access: the application has"
            + " no Spring Security filter chain. Protect the application with Spring Security, set "
            + ALLOW_ANONYMOUS + "=true for an isolated or development deployment, or set " + UI_ENABLED
            + "=false to remove the admin UI.";

    private final ListableBeanFactory beans;
    private final Environment environment;
    private final String securityFilterChainType;
    private volatile boolean securedByFilterChain;
    private volatile boolean consolesPresent;
    private volatile boolean engineToolsPresent;

    public ConsoleAccess(ListableBeanFactory beans, Environment environment) {
        this(beans, environment, SECURITY_FILTER_CHAIN);
    }

    ConsoleAccess(ListableBeanFactory beans, Environment environment, String securityFilterChainType) {
        this.beans = beans;
        this.environment = environment;
        this.securityFilterChainType = securityFilterChainType;
    }

    @Override
    public void afterSingletonsInstantiated() {
        securedByFilterChain = hasFilterChain(beans, securityFilterChainType);
        consolesPresent = hasBeanInPackage(beans, ConsoleAccessInterceptor.CONSOLE_PACKAGE);
        engineToolsPresent = beans.getBeanNamesForType(McpTools.class, false, false).length > 0;
        if (hasBeanOfType(beans, ENGINE_API_ACCESS)) {
            return; // the engine states both rules in one line
        }
        var status = status(securedByFilterChain, allowAnonymous());
        if (securedByFilterChain) {
            log.info(status);
        } else {
            log.warn(status);
        }
    }

    /** Whether a request for a console may go on. */
    public boolean allows() {
        return securedByFilterChain || allowAnonymous();
    }

    public boolean isSecuredByFilterChain() {
        return securedByFilterChain;
    }

    /**
     * Whether the application mounts an EventConductor console ({@code io.mateu.workflow.infra.in.ui}).
     * Then every Mateu UI in it is a console too — the engine's menus and pages can be mounted in any
     * of them, and Mateu's MCP endpoint drives them. Without one (a worker with its own Mateu pages, or
     * {@code workflow.ui.enabled=false}) the application's own UIs are left alone.
     */
    public boolean consolesPresent() {
        return consolesPresent;
    }

    /** Whether the engine's tools ({@link McpTools}) exist, so an MCP server in the application exposes them. */
    public boolean engineToolsPresent() {
        return engineToolsPresent;
    }

    private static boolean hasBeanInPackage(ListableBeanFactory beans, String packagePrefix) {
        for (var name : beans.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = beans.getType(name, false);
            } catch (RuntimeException | LinkageError unresolvable) {
                continue;
            }
            if (type != null && ClassUtils.getUserClass(type).getName().startsWith(packagePrefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean allowAnonymous() {
        return environment.getProperty(ALLOW_ANONYMOUS, Boolean.class, false);
    }

    /** One sentence on how the consoles are protected, for a startup log line. */
    public static String status(boolean securedByFilterChain, boolean allowAnonymous) {
        if (securedByFilterChain) {
            return "Protected by the application's Spring Security: " + CONSOLES + ".";
        }
        if (allowAnonymous) {
            return "OPEN to anyone who can reach this port, because " + ALLOW_ANONYMOUS + " is true and no Spring"
                    + " Security filter chain is configured: " + CONSOLES + ". Only for an isolated deployment.";
        }
        return "Refused (403) to every request, because no Spring Security filter chain is configured: " + CONSOLES
                + ". Add Spring Security, set " + ALLOW_ANONYMOUS + "=true for an isolated or development"
                + " deployment, or set " + UI_ENABLED + "=false to remove the admin UI.";
    }

    /** Whether a bean of {@code securityFilterChainType} exists (false when Spring Security is not on the classpath). */
    public static boolean hasFilterChain(ListableBeanFactory beans, String securityFilterChainType) {
        return hasBeanOfType(beans, securityFilterChainType);
    }

    private static boolean hasBeanOfType(ListableBeanFactory beans, String typeName) {
        try {
            var type = ClassUtils.forName(typeName, ConsoleAccess.class.getClassLoader());
            return beans.getBeanNamesForType(type, false, false).length > 0;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
