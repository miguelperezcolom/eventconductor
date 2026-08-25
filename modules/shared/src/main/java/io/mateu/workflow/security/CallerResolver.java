package io.mateu.workflow.security;

/**
 * Who the current thread is acting for, as an {@link AuthorizationContext}.
 *
 * <p>The one place the engine asks "who is this?", so that the answer can differ per deployment
 * without the flow-authorization checks knowing anything about it. There are two realistic answers
 * and the default implementation serves both: an app that authenticates callers itself (Spring
 * Security — HTTP Basic in the standalone apps, or a resource server), and an app behind a gateway
 * that has already validated the token and forwards it.
 *
 * <p><b>It is allowed to answer "I don't know", and that answer matters.</b> A {@code null} caller
 * holds nothing, so it is denied the moment a definition or a form requires anything — which is the
 * behaviour you want when identity is misconfigured. Nothing here decides <em>whether</em> a caller
 * is denied; that is {@code FlowAuthorizationService}, and only when
 * {@code workflow.security.flow-authorization.enabled} is on.
 *
 * <p>Supply your own bean to answer from somewhere else — a header your gateway sets, a session, a
 * mesh identity — and the checks pick it up unchanged.
 */
public interface CallerResolver {

    /**
     * The caller on whose behalf the current thread is working, or {@code null} when there is none
     * to be had: no request in progress (a Kafka consumer, a scheduler), or a request with nothing
     * on it to identify anybody.
     *
     * <p>Never throws. An identity that cannot be read is not an identity, and turning that into an
     * exception would fail a request that a fail-closed check would have refused with a reason.
     */
    AuthorizationContext current();
}
