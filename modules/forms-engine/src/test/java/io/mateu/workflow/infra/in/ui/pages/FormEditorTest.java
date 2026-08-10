package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.dtos.RunActionRqDto;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The forms editor view embeds the standalone {@code <eventconductor-form-editor>} component the way
 * the workflow graph is embedded (an {@link io.mateu.uidl.data.Element} with the tag, the module
 * import and a {@code value}), and persists an edit the component pushes back through its
 * value-changed event. Mirrors {@code SimpleProcessViewModelTest}.
 */
class FormEditorTest {

    private FormEditor view(FormRepository repository) {
        return new FormEditor(repository);
    }

    private Form form(String id) {
        return new Form(id, "Contact", "A form",
                List.of(new Field("email", "Email", FieldDataType.string, FieldStereotype.email, true, null)));
    }

    @Test
    void loadEmbedsTheComponentWithTheFormValue() {
        var repository = mock(FormRepository.class);
        when(repository.findById("f-1")).thenReturn(Optional.of(form("f-1")));

        var loaded = view(repository).load("f-1");

        var element = loaded.editor;
        assertThat(element.name()).isEqualTo("eventconductor-form-editor");
        assertThat(element.attributes().get("import")).isEqualTo("/eventconductor/form-editor.js");
        // The value carries the form JSON so the component renders the current definition.
        assertThat(element.attributes().get("value")).contains("\"name\"", "Contact", "email");
        // The value-changed event is wired to the save action, so an edit round-trips.
        assertThat(element.on().get("value-changed")).isEqualTo("save");
    }

    @Test
    void saveDeserializesTheEmittedValueAndPersistsIt() {
        var repository = mock(FormRepository.class);
        when(repository.findById("f-1")).thenReturn(Optional.of(form("f-1")));
        var view = view(repository).load("f-1");

        // The component emitted an edited form (a renamed form + one field) as its value.
        var editedJson = """
                {"id":"f-1","name":"Contact us","fields":[
                  {"id":"email","label":"Email","dataType":"string","stereotype":"email","required":true}
                ]}""";
        var httpRequest = mock(HttpRequest.class);
        var rq = mock(RunActionRqDto.class);
        when(httpRequest.runActionRq()).thenReturn(rq);
        when(rq.componentState()).thenReturn(Map.of("value", editedJson));

        view.save(httpRequest);

        var captor = org.mockito.ArgumentCaptor.forClass(Form.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.id()).isEqualTo("f-1");
        assertThat(saved.name()).isEqualTo("Contact us");
        assertThat(saved.fields()).hasSize(1);
        assertThat(saved.fields().get(0).id()).isEqualTo("email");
        assertThat(saved.fields().get(0).dataType()).isEqualTo(FieldDataType.string);
    }

    @Test
    void saveKeepsTheFormIdWhenTheEmittedValueDroppedIt() {
        var repository = mock(FormRepository.class);
        when(repository.findById("f-1")).thenReturn(Optional.of(form("f-1")));
        var view = view(repository).load("f-1");

        // A value with no id must not fork a new form — the loaded formId is kept.
        var httpRequest = mock(HttpRequest.class);
        var rq = mock(RunActionRqDto.class);
        when(httpRequest.runActionRq()).thenReturn(rq);
        when(rq.componentState()).thenReturn(Map.of("value",
                "{\"name\":\"No id\",\"fields\":[{\"id\":\"a\",\"label\":\"A\",\"dataType\":\"string\"}]}"));

        view.save(httpRequest);

        var captor = org.mockito.ArgumentCaptor.forClass(Form.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo("f-1");
    }

    @Test
    void saveIgnoresAnAbsentOrInvalidValueWithoutPersisting() {
        var repository = mock(FormRepository.class);
        when(repository.findById("f-1")).thenReturn(Optional.of(form("f-1")));
        var view = view(repository).load("f-1");

        var httpRequest = mock(HttpRequest.class);
        var rq = mock(RunActionRqDto.class);
        when(httpRequest.runActionRq()).thenReturn(rq);
        when(rq.componentState()).thenReturn(Map.of()); // nothing emitted

        view.save(httpRequest);

        verify(repository, org.mockito.Mockito.never()).save(any());
    }
}
