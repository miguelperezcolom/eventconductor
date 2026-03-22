package io.mateu.workflow.usersservice.domain.aggregates.shared.vo;

public record Name(String name) {

    public Name {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    }
}
