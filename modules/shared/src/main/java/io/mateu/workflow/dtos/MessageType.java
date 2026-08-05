package io.mateu.workflow.dtos;

public enum MessageType {
    Info, Error;

    /**
     * Whether a persisted {@code messageType} names an error.
     *
     * <p>Case-insensitive, and that is the point. It is written as {@link #name()} — "Error" — and
     * every reader compared it against the lowercase literal, so the Errors tab of a process was
     * empty however badly things had gone, the Messages tab showed the errors instead, and the
     * graph's hover card never had a reason to show. Rows written before this are matched too,
     * which is why it reads leniently rather than the writer being changed.
     */
    public static boolean isError(String messageType) {
        return Error.name().equalsIgnoreCase(messageType);
    }
}
