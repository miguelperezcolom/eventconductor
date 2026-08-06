package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.usecases.gitimport.ImportFormsFromGitUseCase;
import io.mateu.workflow.domain.Form;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The forms MCP tool surface, pinned as literals, plus the one mapping in it that can throw.
 *
 * <p>Same reasoning as the workflow engine's {@code WorkflowMcpToolSurfaceTest}: these tools are
 * part of the 1.0 contract and their callers are configured outside this repository, so a rename
 * has to fail here or it fails silently in someone else's assistant.
 */
class FormsMcpToolSurfaceTest {

    private static final String[] PUBLISHED_TOOLS = {
            "listForms",
            "listFormExecutions",
            "getFormExecution",
            "importFormsFromGit",
    };

    private final FormRepository formRepository = mock(FormRepository.class);
    private final FormExecutionRepository formExecutionRepository = mock(FormExecutionRepository.class);
    private final FormsMcpTools tools = new FormsMcpTools(
            formRepository, formExecutionRepository, mock(ImportFormsFromGitUseCase.class));

    @Test
    void thePublishedToolsAreExactlyTheOnesTheContractNames() {
        var names = Arrays.stream(FormsMcpTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .toArray(String[]::new);

        assertThat(names).containsExactlyInAnyOrder(PUBLISHED_TOOLS);
    }

    @Test
    void everyToolIsPublicAndCarriesADescriptionAModelCanActOn() {
        for (var method : FormsMcpTools.class.getDeclaredMethods()) {
            var tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("%s is annotated @Tool but is not public", method.getName()).isTrue();
            assertThat(tool.description())
                    .as("%s has no description for a model to decide on", method.getName()).isNotBlank();
        }
    }

    /**
     * A form definition with no fields is legal — an imported stub, a form still being written —
     * and counting them must not be what takes the tool down. The failure would not be local to the
     * bad row either: the whole call fails, so an assistant asking "what forms exist?" gets an
     * error instead of the list of the ones that are fine.
     */
    @Test
    void aFormWithoutFieldsIsCountedAsZeroRatherThanThrowing() {
        when(formRepository.findAll()).thenReturn(List.of(
                new Form("f-1", "Contact", null, null),
                new Form("f-2", "Booking", "Two fields", List.of())));

        assertThat(tools.listForms()).extracting(FormsMcpTools.FormSummary::fieldCount)
                .containsExactly(0, 0);
    }

    @Test
    void anEmptyEngineListsNothingRatherThanFailing() {
        when(formRepository.findAll()).thenReturn(List.of());
        when(formExecutionRepository.findAll()).thenReturn(List.of());

        assertThat(tools.listForms()).isEmpty();
        assertThat(tools.listFormExecutions()).isEmpty();
    }
}
