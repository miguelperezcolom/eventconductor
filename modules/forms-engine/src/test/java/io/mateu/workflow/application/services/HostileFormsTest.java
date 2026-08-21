package io.mateu.workflow.application.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.services.FormValidator.FormValidationException;
import io.mateu.workflow.domain.Form;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HARD-FORM-01..07 — a form definition written to break the engine, or the person filling it in.
 *
 * <p>A form arrives the same way a workflow does: from a git repository, a watched directory or the
 * editor. It then decides what a human is shown and which variables their answers become, so a bad
 * one is not only a broken screen — it is a process running on values nobody meant to give it. Same
 * bar as the workflow definitions: refused with a message naming what is wrong, or accepted and
 * harmless.
 */
class HostileFormsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FormValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        validator = new FormValidator();
        validator.init();
    }

    private Form parse(String json) throws Exception {
        return objectMapper.readValue(json, Form.class);
    }

    private String formWith(String fields) {
        return """
                { "id": "hostile", "name": "Hostile", "fields": [ %s ] }
                """.formatted(fields);
    }

    private static final String ONE_FIELD =
            "{ \"id\": \"amount\", \"label\": \"Amount\", \"dataType\": \"string\" }";

    /** HARD-FORM-01. A field with no id has no variable to write to. */
    @Test
    void aFieldWithoutAnIdIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse(formWith(
                "{ \"label\": \"No id\", \"dataType\": \"string\" }"))))
                .isInstanceOf(FormValidationException.class);
    }

    /** HARD-FORM-02. An empty id is the same hole spelled differently. */
    @Test
    void aBlankFieldIdIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse(formWith(
                "{ \"id\": \"\", \"label\": \"Blank\", \"dataType\": \"string\" }"))))
                .isInstanceOf(FormValidationException.class);
    }

    /** HARD-FORM-03. A data type the renderer has no branch for. */
    @Test
    void anUnknownDataTypeIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse(formWith(
                "{ \"id\": \"x\", \"label\": \"X\", \"dataType\": \"executable\" }"))))
                .isInstanceOf(Exception.class);
    }

    /**
     * HARD-FORM-04. Two fields answering to one id. Which of them a submitted value belongs to is
     * arbitrary, so the value a downstream step reads is arbitrary too — and a required field can
     * be satisfied by its namesake.
     */
    @Test
    void duplicateFieldIdsAreRefused() {
        assertThatThrownBy(() -> validator.validate(parse(formWith(
                ONE_FIELD + ", { \"id\": \"amount\", \"label\": \"Amount again\", \"dataType\": \"integer\" }"))))
                // IllegalStateException, like the workflow definition invariants: a broken
                // invariant is not a schema violation, and the two are reported as what they are.
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate field id")
                .hasMessageContaining("amount");
    }

    /** HARD-FORM-05. Choices written in and choices fetched are alternatives, not both. */
    @Test
    void aFieldDeclaringBothItsChoicesAndWhereToFetchThemIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse(formWith("""
                { "id": "country", "label": "Country", "dataType": "string",
                  "options": [ { "value": "ES", "label": "Spain" } ],
                  "optionsSource": { "url": "/api/countries" } }
                """))))
                .isInstanceOf(FormValidationException.class);
    }

    /** HARD-FORM-06. A misspelled key is a field that does not do what its author thought. */
    @Test
    void anUnknownFieldPropertyIsRefused() {
        assertThatThrownBy(() -> validator.validate(parse(formWith(
                "{ \"id\": \"x\", \"label\": \"X\", \"dataType\": \"string\", \"requiered\": true }"))))
                .isInstanceOf(Exception.class);
    }

    /**
     * HARD-FORM-07. A label is free text and stays free text. It is rendered by the UI, which is
     * what escapes it (see the browser suite); refusing a label because it contains a bracket would
     * make the validator the problem.
     */
    @Test
    void aLabelThatLooksLikeAnExploitIsAcceptedAsText() {
        assertThatNoException().isThrownBy(() -> validator.validate(parse("""
                { "id": "xss", "name": "<script>alert(1)</script>", "fields": [
                    { "id": "f", "label": "<img src=x onerror=alert(1)>", "dataType": "string" }
                ] }
                """)));
    }
}
