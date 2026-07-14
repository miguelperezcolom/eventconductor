package io.mateu.workflow.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RuleType {
    EXPRESSION("expression"),
    DECISION_TABLE("decision-table");

    private final String label;

    RuleType(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }

    @JsonCreator
    public static RuleType fromLabel(String label) {
        for (RuleType type : values()) {
            if (type.label.equals(label)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown rule type: " + label);
    }
}
