package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.services.VariableProtection;

/** The publishers' one call into {@link VariableProtection}, tolerant of a publisher built without one (a unit test). */
final class VariableProtectionSupport {

    private VariableProtectionSupport() {
    }

    static <E> E seal(VariableProtection protection, E event) {
        return protection == null ? event : protection.sealEvent(event);
    }
}
