package io.mateu.workflow.domain;

import io.mateu.core.infra.declarative.Entity;
import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.GeneratedValue;
import io.mateu.uidl.annotations.HiddenInCreate;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.uidl.annotations.Style;

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
) implements Entity<String> {
}
