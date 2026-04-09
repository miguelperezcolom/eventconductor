package io.mateu.workflow.contentservice.infra.in.ui.pages.content;

import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.Lookup;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.CountryCode;
import io.mateu.workflow.contentservice.domain.aggregates.content.vo.LanguageCode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record ContentValueViewModel(
        @NotNull
        CountryCode country,
        @NotNull
        LanguageCode language,
        @Stereotype(FieldStereotype.textarea)
        @Colspan(2)
        @Style("width: 100%;")
        String value
) {
}
