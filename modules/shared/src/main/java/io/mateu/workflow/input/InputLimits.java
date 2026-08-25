package io.mateu.workflow.input;

import io.mateu.workflow.dtos.Variable;

import java.util.List;
import java.util.function.Function;

/**
 * How much of anything one untrusted caller may hand the engine in one event.
 *
 * <p><b>Why a ceiling exists at all.</b> The engine deliberately does not truncate what it is given:
 * the columns that hold variables are {@code TEXT} precisely so a large value survives whole, because
 * a value silently cut at some column width is worse than one refused — the process then runs on data
 * that is not what anybody sent. That decision is about <em>a</em> large value, and it says nothing
 * about how large. Nothing did. A caller could POST two hundred megabytes of JSON to the message API
 * and the engine would parse it into heap, carry it through the outbox, and write it to a row, on a
 * thread that is also running everybody else's processes.
 *
 * <p>So the ceilings here draw the line between "large" and "absurd", and they are set where a human
 * being's real payload never reaches: a megabyte in a single variable is already far past anything a
 * business process carries, and it is the number the forms engine has enforced on a submitted value
 * for exactly this reason. What changes is that it now applies to every channel, not just the browser.
 *
 * <p><b>Identifiers are separate, and they are not about memory.</b> {@code process_entity.id},
 * {@code business_key} and {@code awaiting_correlation_key} are {@code VARCHAR(255)} in the schema —
 * the one family of columns that is deliberately bounded, because they are keys. An over-long one does
 * not fail here, in a check with a message; it fails in the JDBC driver, inside the transaction that
 * was saving a running process, which is a far worse place for it. {@link #MAX_IDENTIFIER_LENGTH} is
 * that column width, checked before the row is built.
 *
 * <p>Every limit can be raised per deployment through the system property named beside it, without a
 * rebuild, for the one installation that proves a number wrong.
 */
public final class InputLimits {

    /**
     * Longest accepted identifier — a business key, a correlation key, a message name, a workflow
     * definition id, a process or step execution id. 255 because that is what the columns holding
     * them are. Override: {@code eventconductor.input.max-identifier-length}.
     */
    public static final int MAX_IDENTIFIER_LENGTH =
            Integer.getInteger("eventconductor.input.max-identifier-length", 255);

    /**
     * Longest accepted single value — one variable, one log message, one injected step document.
     * A megabyte: generous enough that no real payload meets it, small enough that one caller cannot
     * decide how much memory the engine spends. Deliberately the same number the forms engine already
     * enforces on a submitted field. Override: {@code eventconductor.input.max-value-length}.
     */
    public static final int MAX_VALUE_LENGTH =
            Integer.getInteger("eventconductor.input.max-value-length", 1_048_576);

    /**
     * Most variables one event may carry. A process with five hundred variables is not a process with
     * a lot of state, it is a caller sending a data structure through the wrong door.
     * Override: {@code eventconductor.input.max-variables}.
     */
    public static final int MAX_VARIABLES =
            Integer.getInteger("eventconductor.input.max-variables", 500);

    /**
     * Most one event may carry across all its variables together. Without this the per-value limit is
     * no limit at all: five hundred variables of a megabyte each clears every other check on this
     * class and is still half a gigabyte.
     * Override: {@code eventconductor.input.max-total-length}.
     */
    public static final long MAX_TOTAL_LENGTH =
            Long.getLong("eventconductor.input.max-total-length", 8L * 1_048_576L);

    /** Enough of an offending string to recognise it; the rejection must not quote a megabyte back. */
    private static final int EXCERPT = 80;

    private InputLimits() {
    }

    /**
     * Rejects an identifier too long for the column that will hold it.
     *
     * @param value the identifier; {@code null} and blank pass, because "no key" is every caller's
     *              own case to handle and never a hazard
     * @param what  what the identifier is, for the message — e.g. {@code "businessKey"}
     */
    public static void checkIdentifier(String value, String what) {
        if (value != null && value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new InputRejectedException(what + " is " + value.length() + " characters long ("
                    + excerpt(value) + "), over the " + MAX_IDENTIFIER_LENGTH + " character limit");
        }
    }

    /** Rejects a single free-text field — a log message, a step document — that is over the value limit. */
    public static void checkText(String value, String what) {
        if (value != null && value.length() > MAX_VALUE_LENGTH) {
            throw new InputRejectedException(what + " is " + value.length() + " characters long, over the "
                    + MAX_VALUE_LENGTH + " character limit");
        }
    }

    /**
     * Rejects a variable list that is too long, too numerous, or names something too long to be a name.
     *
     * @param variables the variables; {@code null} and empty pass
     * @param what      what is carrying them, for the message — e.g. {@code "a process creation"}
     */
    public static void checkVariables(List<Variable> variables, String what) {
        checkNamedValues(variables, Variable::name, Variable::value, what);
    }

    /**
     * The same check for anything else shaped like a name and a value — the engine's own
     * {@code Variable} aggregate, a form's {@code Value}. Accessors rather than a common interface
     * because those types live in modules that do not know about each other, and copying a list into
     * a shared shape purely in order to measure it would allocate exactly the memory being defended.
     *
     * @param items the items; {@code null} and empty pass
     * @param name  how to read an item's name
     * @param value how to read an item's value
     * @param what  what is carrying them, for the message
     */
    public static <T> void checkNamedValues(List<T> items, Function<T, String> name,
                                            Function<T, String> value, String what) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (items.size() > MAX_VARIABLES) {
            throw new InputRejectedException(what + " carries " + items.size()
                    + " variables, over the limit of " + MAX_VARIABLES);
        }
        long total = 0;
        for (var item : items) {
            if (item == null) {
                continue;
            }
            var variableName = name.apply(item);
            if (variableName != null && variableName.length() > MAX_IDENTIFIER_LENGTH) {
                throw new InputRejectedException(what + " carries a variable whose name is "
                        + variableName.length() + " characters long (" + excerpt(variableName)
                        + "), over the " + MAX_IDENTIFIER_LENGTH + " character limit");
            }
            var variableValue = value.apply(item);
            if (variableValue == null) {
                continue;
            }
            if (variableValue.length() > MAX_VALUE_LENGTH) {
                throw new InputRejectedException(what + " carries variable '" + excerpt(variableName)
                        + "' with " + variableValue.length() + " characters, over the "
                        + MAX_VALUE_LENGTH + " character limit");
            }
            total += variableValue.length();
            if (total > MAX_TOTAL_LENGTH) {
                throw new InputRejectedException(what + " carries more than " + MAX_TOTAL_LENGTH
                        + " characters across its variables, which is over the limit for one event");
            }
        }
    }

    private static String excerpt(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= EXCERPT ? value : value.substring(0, EXCERPT) + "…";
    }

    /**
     * A refusal. {@link RuntimeException} rather than a checked exception because the callers are
     * event handlers and controllers, and because what the engine does with it is decided by where it
     * was thrown: the consumer parks the event on the dead-letter destination, the REST controller
     * answers 400, and the forms UI shows the message on the screen. It is deliberately <em>not</em>
     * one of the failures {@code EventFailures} calls retryable — the same input will be refused for
     * exactly the same reason forever, so retrying it is an infinite loop nobody reads.
     */
    public static class InputRejectedException extends RuntimeException {
        public InputRejectedException(String message) {
            super(message);
        }
    }
}
