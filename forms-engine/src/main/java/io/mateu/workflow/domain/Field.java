package io.mateu.workflow.domain;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;

public record Field(
        String id,
        String label,
        FieldDataType dataType,
        FieldStereotype stereotype,
        boolean required,
        String description
) {
}
