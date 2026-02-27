package io.mateu.workflow.domain;

import io.mateu.core.infra.declarative.Entity;

import java.util.List;

public record Form(
        String id,
        String name,
        String description,
        List<Field> fields
) implements Entity<String> {
}
