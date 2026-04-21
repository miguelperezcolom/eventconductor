package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.changes;

import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.uidl.interfaces.TitleSupplier;
import io.mateu.workflow.controlplaneservice.application.usecases.compare.ComparisonResult;
import lombok.NoArgsConstructor;

@FormLayout(columns = 1)
@Style("max-width:1300px;margin: auto;")
@NoArgsConstructor
public class ComparisonResultPage implements TitleSupplier {

    @Hidden
    String page;

    @Tab("Masked")
    @Stereotype(FieldStereotype.image)
    String masked;

    @Tab("Transparent masked")
    @Stereotype(FieldStereotype.image)
    String transparentMasked;

    @Tab("Diff")
    @Stereotype(FieldStereotype.image)
    String diff;


    ComparisonResultPage(ComparisonResult result) {
        this.page = result.page();
        this.masked = result.maskedUrl();
        this.transparentMasked = result.transparentMaskedUrl();
        this.diff = result.diffUrl();
    }


    @Toolbar
    Object backToList() {
        return MateuBeanProvider.getBean(Changes.class);
    }

    @Override
    public String title() {
        return "Changes on " + page;
    }
}
