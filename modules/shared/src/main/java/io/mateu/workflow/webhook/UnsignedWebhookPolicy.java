package io.mateu.workflow.webhook;

import java.util.function.Predicate;

/**
 * Whether a git-import webhook may come in unsigned — no webhook secret configured. A webhook makes the
 * engine clone repositories and replace definitions, so since 2.28.0 an unsigned one is refused with
 * 401 unless the application already decides who gets in, by the same rule as the engine's REST API:
 *
 * <ul>
 *   <li>a Spring Security filter chain protects the application (the request has passed its
 *       authentication), or</li>
 *   <li>{@code workflow.message-api.allow-anonymous=true} (an isolated deployment).</li>
 * </ul>
 *
 * Spring-free, so each engine module applies it with its own bean lookups.
 */
public final class UnsignedWebhookPolicy {

    /** The property that opens the engine's API, and unsigned webhooks, to anonymous callers. */
    public static final String ALLOW_ANONYMOUS = "workflow.message-api.allow-anonymous";

    static final String SECURITY_FILTER_CHAIN = "org.springframework.security.web.SecurityFilterChain";

    private UnsignedWebhookPolicy() {
    }

    /**
     * Whether a bean of Spring Security's {@code SecurityFilterChain} exists — false when Spring
     * Security is not on the classpath.
     *
     * @param classLoader where to look the type up
     * @param hasBeanOfType whether the application context holds a bean of a type
     */
    public static boolean filterChainPresent(ClassLoader classLoader, Predicate<Class<?>> hasBeanOfType) {
        return beanPresent(SECURITY_FILTER_CHAIN, classLoader, hasBeanOfType);
    }

    static boolean beanPresent(String typeName, ClassLoader classLoader, Predicate<Class<?>> hasBeanOfType) {
        try {
            return hasBeanOfType.test(Class.forName(typeName, false, classLoader));
        } catch (ClassNotFoundException | LinkageError noSpringSecurity) {
            return false;
        }
    }

    /** What a refused unsigned webhook is told; {@code secretProperty} is the module's webhook-secret key. */
    public static String refusal(String secretProperty) {
        return "Unsigned webhook refused: set " + secretProperty + " (and the same secret in the git provider),"
                + " protect the application with Spring Security, or set " + ALLOW_ANONYMOUS
                + "=true for an isolated deployment.";
    }
}
