package io.mateu.workflow.infra.in.ui.security;

import io.mateu.core.infra.security.IdentityResolver;
import io.mateu.uidl.interfaces.HttpRequest;

import java.util.Optional;

/** Who is using a task page: the identity Mateu resolved for the request, if anyone signed in. */
public final class SignedInUser {

    private SignedInUser() {
    }

    public static Optional<String> name(HttpRequest httpRequest) {
        return httpRequest == null ? Optional.empty()
                : Optional.ofNullable(IdentityResolver.resolve(httpRequest).name());
    }
}
