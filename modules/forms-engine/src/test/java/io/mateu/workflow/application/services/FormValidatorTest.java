package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormValidatorTest {

    private FormValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new FormValidator(new ObjectMapper());
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
}
