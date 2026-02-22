package io.mateu.workflow.domain;

import java.util.List;

public record Form(
        String name,
        String description,
        List<Field> fields
) {
}
