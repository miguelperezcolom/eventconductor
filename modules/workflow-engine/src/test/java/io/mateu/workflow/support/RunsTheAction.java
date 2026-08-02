package io.mateu.workflow.support;

import org.mockito.stubbing.Answer;

/**
 * Stubs {@code ProcessLockService.runExclusively} as "exclusivity granted": the action is
 * actually run, which is the whole point — a mock that only returned true would report every
 * handler as doing nothing.
 */
public final class RunsTheAction {

    public static Answer<Boolean> granted() {
        return invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        };
    }

    private RunsTheAction() {
    }
}
