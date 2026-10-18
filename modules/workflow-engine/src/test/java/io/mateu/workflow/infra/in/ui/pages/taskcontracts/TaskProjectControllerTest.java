package io.mateu.workflow.infra.in.ui.pages.taskcontracts;

import io.mateu.workflow.codegen.ProjectSpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskProjectControllerTest {

    private final TaskProjectService service = mock(TaskProjectService.class);
    private final TaskProjectController controller = new TaskProjectController(service);

    @Test
    void theArchiveIsAnAttachmentNamedAfterTheGroup() {
        when(service.zip("billing", ProjectSpec.Variant.TASK_MODULE)).thenReturn(new byte[] {1});
        var response = controller.module("billing");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"billing-tasks.zip\"");
    }

    @Test
    void aGroupThatCouldBreakTheHeaderIsRefused() {
        for (var group : new String[] {"a\"; filename=evil.exe", "a\r\nSet-Cookie: x=y", "../etc", "", " x"}) {
            assertThatThrownBy(() -> controller.service(group))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(service, never()).zip(anyString(), any());
    }
}
