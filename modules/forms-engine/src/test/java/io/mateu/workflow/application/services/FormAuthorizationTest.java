package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.Form;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AUTHZ-FORM-01..03 — a form file may say who the work is for, and a form file that says nothing
 * still means what it always meant.
 *
 * <p>Both halves matter for the import path: the schema is {@code additionalProperties: false}, so
 * without the schema knowing these keys a form declaring them would be refused outright, and without
 * the record defaulting them every form written before today would come back requiring nothing —
 * which is the answer that has to survive.
 */
class FormAuthorizationTest {

    private FormValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new FormValidator();
        validator.init();
    }

    private static final String FIELDS =
            "\"fields\":[{\"id\":\"note\",\"label\":\"Note\",\"dataType\":\"string\"}]";

    /** AUTHZ-FORM-01. Declared requirements parse and survive validation. */
    @Test
    void aFormMaySayWhichScopesAndRolesItsWorkNeeds() {
        var form = pojoFromJson("{\"name\":\"Approve refund\"," + FIELDS
                + ",\"requiredScopes\":[\"payments:approve\"],\"requiredRoles\":[\"finance\"]}", Form.class);

        assertThat(form.requiredScopes()).containsExactly("payments:approve");
        assertThat(form.requiredRoles()).containsExactly("finance");
        assertThatCode(() -> validator.validate(form)).doesNotThrowAnyException();
    }

    /** AUTHZ-FORM-02. A form that says nothing requires nothing — never null, so no caller checks. */
    @Test
    void aFormWrittenBeforeThisExistedRequiresNothing() {
        var form = pojoFromJson("{\"name\":\"Collect note\"," + FIELDS + "}", Form.class);

        assertThat(form.requiredScopes()).isEmpty();
        assertThat(form.requiredRoles()).isEmpty();
        assertThatCode(() -> validator.validate(form)).doesNotThrowAnyException();
    }

    /** AUTHZ-FORM-03. And the same is true of one built through the constructor callers already use. */
    @Test
    void theConstructorThatPredatesThisStillBuildsAnOpenForm() {
        var form = new Form("f-1", "Collect note", "", List.of());

        assertThat(form.requiredScopes()).isEmpty();
        assertThat(form.requiredRoles()).isEmpty();
    }
}
