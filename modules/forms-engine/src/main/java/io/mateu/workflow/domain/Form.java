package io.mateu.workflow.domain;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.interfaces.Identifiable;

import java.util.List;

@Style("width: 100%;")
public record Form(
        @GeneratedValue(UUIDValueGenerator.class)
        @HiddenInCreate
        String id,
        String name,
        String description,
        @Colspan(2)
        @MasterDetail(minHeightWhenDetailVisible = "26rem;")
        List<Field> fields
) implements Identifiable {
}
