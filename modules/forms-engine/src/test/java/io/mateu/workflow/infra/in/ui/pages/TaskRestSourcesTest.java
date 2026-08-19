package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.data.RestSourceKind;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskUseCase;
import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.FieldOptionsSource;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the task page tells mateu it declared, so a proxy fetch has something to resolve against.
 * Everything here is read from the stored definition — which is the condition of the proxy not
 * being an open relay — and the only thing taken from the request is which task the page is on.
 */
class TaskRestSourcesTest {

    private final FormExecutionRepository executions = mock(FormExecutionRepository.class);
    private final FormRepository forms = mock(FormRepository.class);
    private Task task;

    private Field picker(String id, String url, boolean proxy) {
        return new Field(id, id, FieldDataType.string, FieldStereotype.select, false, null, List.of(),
                new FieldOptionsSource(url, null, null, null, null, "code", "name", proxy));
    }

    private Field plain(String id) {
        return new Field(id, id, FieldDataType.string, FieldStereotype.regular, false, null);
    }

    @BeforeEach
    void setUp() {
        task = new Task(executions, forms, mock(StreamBridge.class), mock(CompleteTaskUseCase.class));
        task._taskId = "t-1";
        when(executions.findById("t-1"))
                .thenReturn(Optional.of(FormExecution.builder().id("t-1").formId("f-1").build()));
    }

    @Test
    void declaresOneSourcePerFieldThatHasOne() {
        when(forms.findById("f-1")).thenReturn(Optional.of(new Form("f-1", "Overbooking", null,
                List.of(plain("comments"),
                        picker("hotel", "https://pms.internal/hotels?token=${secret.PMS_TOKEN}", true),
                        picker("room", "https://pms.internal/rooms", false)))));

        var declared = task.declaredRestSources();

        assertThat(declared).extracting(d -> d.kind()).containsOnly(RestSourceKind.OPTIONS);
        assertThat(declared).extracting(d -> d.id()).containsExactly("hotel", "room");
        assertThat(declared).extracting(d -> d.source().url())
                .containsExactly("https://pms.internal/hotels?token=${secret.PMS_TOKEN}",
                        "https://pms.internal/rooms");
        // The flag rides across, or the server would fetch for a field that never asked it to.
        assertThat(declared).extracting(d -> d.source().proxy()).containsExactly(true, false);
        assertThat(declared.get(0).source().valuePath()).isEqualTo("code");
    }

    @Test
    void aFormWhoseFieldsFetchNothingDeclaresNothing() {
        when(forms.findById("f-1")).thenReturn(Optional.of(
                new Form("f-1", "Contact", null, List.of(plain("name")))));

        assertThat(task.declaredRestSources()).isEmpty();
    }

    @Test
    void aTaskThatIsNotThereDeclaresNothing() {
        when(executions.findById("t-1")).thenReturn(Optional.empty());
        assertThat(task.declaredRestSources()).isEmpty();

        task._taskId = null;
        assertThat(task.declaredRestSources()).isEmpty();
    }
}
