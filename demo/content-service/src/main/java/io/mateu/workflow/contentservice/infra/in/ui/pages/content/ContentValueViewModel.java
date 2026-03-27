package io.mateu.workflow.contentservice.infra.in.ui.pages.content;

import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.ForeignKey;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.data.FieldStereotype;

public record ContentValueViewModel(
        String country,
        String language,
        @Stereotype(FieldStereotype.textarea)
        @Colspan(2)
        @Style("width: 100%;")
        String value
) {
}
