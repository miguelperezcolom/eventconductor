package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum StepType {

    START, ACTION, JOIN, FORK, END, USER_TASK, PROCESS, TIMER, WAIT_FOR_MESSAGE, SEND_MESSAGE, RULE, DYNAMIC;

    /**
     * Accepts the pre-rename alias {@code MESSAGE} (now {@code WAIT_FOR_MESSAGE}) so that the
     * persisted stepJson of in-flight processes and old definition files keep deserializing.
     */
    @JsonCreator
    public static StepType fromJson(String value) {
        if ("MESSAGE".equals(value)) {
            return WAIT_FOR_MESSAGE;
        }
        return valueOf(value);
    }

}
