package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormValidatorTest {

    private FormValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new FormValidator();
        validator.init();
    }

    private Field field(String id) {
        return new Field(id, "Label " + id, FieldDataType.string, FieldStereotype.regular, false, "");
    }

    @Test
    void validFormPassesValidation() {
        var form = new Form("f-1", "My Form", "desc", List.of(field("f1")));
        assertThatNoException().isThrownBy(() -> validator.validate(form));
    }

    @Test
    void formWithoutNameFailsValidation() {
        var form = new Form("f-1", null, "desc", List.of(field("f1")));
        assertThatThrownBy(() -> validator.validate(form))
                .isInstanceOf(FormValidator.FormValidationException.class);
    }

    @Test
    void aFieldMayOfferOptions() {
        var decision = new Field("decision", "Decision", FieldDataType.string, FieldStereotype.radio,
                true, "", List.of(new io.mateu.workflow.domain.FieldOption("WALK", "Walk the guest"),
                        new io.mateu.workflow.domain.FieldOption("REJECT")));

        assertThatNoException().isThrownBy(() ->
                validator.validate(new Form("f-1", "My Form", "desc", List.of(decision))));
    }

    @Test
    void anOptionWithoutAValueFailsValidation() {
        var decision = new Field("decision", "Decision", FieldDataType.string, FieldStereotype.radio,
                true, "", List.of(new io.mateu.workflow.domain.FieldOption(null, "Walk the guest")));

        assertThatThrownBy(() -> validator.validate(new Form("f-1", "My Form", "desc", List.of(decision))))
                .isInstanceOf(FormValidator.FormValidationException.class);
    }

    @Test
    void anOptionLabelDefaultsToItsValue() {
        assertThat(new io.mateu.workflow.domain.FieldOption("REJECT").label()).isEqualTo("REJECT");
        assertThat(new io.mateu.workflow.domain.FieldOption("REJECT", "  ").label()).isEqualTo("REJECT");
    }

    @Test
    void aFieldMayFetchItsChoicesFromARestEndpoint() {
        var country = new Field("country", "Country", FieldDataType.string, FieldStereotype.select,
                true, "", List.of(), new io.mateu.workflow.domain.FieldOptionsSource(
                        "https://restcountries.com/v3.1/all", "cca2", "name.common"));

        assertThatNoException().isThrownBy(() ->
                validator.validate(new Form("f-1", "My Form", "desc", List.of(country))));
    }

    @Test
    void aFieldCannotBothListItsChoicesAndFetchThem() {
        var confused = new Field("country", "Country", FieldDataType.string, FieldStereotype.select,
                true, "", List.of(new io.mateu.workflow.domain.FieldOption("ES", "Spain")),
                new io.mateu.workflow.domain.FieldOptionsSource("https://restcountries.com/v3.1/all", "cca2", "name.common"));

        assertThatThrownBy(() -> validator.validate(new Form("f-1", "My Form", "desc", List.of(confused))))
                .isInstanceOf(FormValidator.FormValidationException.class);
    }

    @Test
    void aSourceWithoutAUrlFailsValidation() {
        var country = new Field("country", "Country", FieldDataType.string, FieldStereotype.select,
                true, "", List.of(), new io.mateu.workflow.domain.FieldOptionsSource(null, "cca2", "name.common"));

        assertThatThrownBy(() -> validator.validate(new Form("f-1", "My Form", "desc", List.of(country))))
                .isInstanceOf(FormValidator.FormValidationException.class);
    }
}
