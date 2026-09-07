package io.mateu.workflow.application.out;

/**
 * The event is defective in a way no retry can fix: it names something that does not exist — a step
 * execution, a process, a workflow definition. It must be parked, never retried.
 *
 * <p>A marker for {@link io.mateu.workflow.application.services.EventFailures}. Classifying these
 * cases by an explicit type, rather than by the incidental {@link java.util.NoSuchElementException}
 * an {@code orElseThrow()} happens to throw, makes the park decision intentional instead of
 * accidental — and lets the classifier keep it parked even when it surfaces wrapped in something
 * that would otherwise look retryable (a data-access exception raised while loading the missing
 * row). Without the explicit type, such a wrap would flip a defective event to "retry forever".
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }
}
