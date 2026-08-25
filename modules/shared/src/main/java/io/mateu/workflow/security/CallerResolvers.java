package io.mateu.workflow.security;

import java.util.List;

/**
 * The resolvers in order of how much the answer can be trusted: the first one that recognises the
 * caller wins.
 *
 * <p>Order is the whole point. An identity this application authenticated itself outranks one it
 * was handed in a header, so turning on {@code trust-forwarded-token} for the gateway case cannot
 * quietly override a real login — and a request carrying both is answered by the one that was
 * verified.
 */
public class CallerResolvers implements CallerResolver {

    private final List<CallerResolver> delegates;

    public CallerResolvers(List<CallerResolver> delegates) {
        this.delegates = delegates == null ? List.of() : List.copyOf(delegates);
    }

    @Override
    public AuthorizationContext current() {
        for (var delegate : delegates) {
            var caller = delegate.current();
            if (caller != null) {
                return caller;
            }
        }
        return null;
    }
}
