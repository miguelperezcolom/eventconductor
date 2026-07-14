package io.mateu.workflow.domain;

import java.util.List;

public record Rule(
        String id,
        String name,
        String description,
        RuleType type,
        int version,
        int salience,
        List<String> tags,
        // expression rules
        String when,
        List<Assignment> then,
        // decision-table rules
        List<String> inputs,
        List<String> outputs,
        List<DecisionRow> rows,
        HitPolicy hitPolicy
) {

    public boolean hasTag(String tag) {
        return tags != null && tags.contains(tag);
    }

    @Override
    public String toString() {
        return name != null ? name : "New rule";
    }
}
