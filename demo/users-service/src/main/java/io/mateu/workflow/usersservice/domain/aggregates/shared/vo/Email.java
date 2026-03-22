package io.mateu.workflow.usersservice.domain.aggregates.shared.vo;

public record Email(String email) {

    public Email {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email is required");
        if (!email.contains("@")) throw new IllegalArgumentException("invalid email");
    }

}
