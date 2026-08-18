package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.application.usecases.directoryimport.ImportFormsFromDirectoryUseCase;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Which files in a repository are a form, and how each is parsed. */
class ImportFormsFileTypesTest {

    private final FormRepository repository = mock(FormRepository.class);
    private final ImportFormsFromDirectoryUseCase useCase = new ImportFormsFromDirectoryUseCase(
            mock(DirectoryImportProperties.class), repository, mock(FormsMetrics.class), new InMemoryImportedDefinitionsRegistry());

    private void write(Path dir, String name, String body) throws IOException {
        Files.writeString(dir.resolve(name), body);
    }

    private List<Form> imported(Path repo) throws IOException {
        useCase.scanAndImport(repo, new ArrayList<>(), new ArrayList<>(), new HashSet<>());
        var saved = ArgumentCaptor.forClass(Form.class);
        verify(repository, atLeast(0)).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    void aFormSavedByTheVisualEditorIsImported(@TempDir Path repo) throws Exception {
        // .ecform is what the IDE plugins register and the editor writes. It parses as YAML.
        write(repo, "approval.ecform", """
                id: approval
                name: Approval
                fields:
                  - id: decision
                    label: Decision
                    dataType: string
                    stereotype: radio
                    options:
                      # Quoted on purpose: YAML 1.1 reads a bare YES/NO/ON/OFF as a boolean, and an
                      # option value of "true" is not what anyone writing YES meant.
                      - value: "YES"
                        label: Approve
                      - value: "NO"
                """);

        var forms = imported(repo);

        assertThat(forms).extracting(Form::id).containsExactly("approval");
        assertThat(forms.get(0).fields().get(0).options())
                .extracting(io.mateu.workflow.domain.FieldOption::value)
                .containsExactly("YES", "NO");
        assertThat(forms.get(0).fields().get(0).options())
                .extracting(io.mateu.workflow.domain.FieldOption::label)
                .containsExactly("Approve", "NO");
    }

    @Test
    void jsonAndYamlAreStillImportedAndAnythingElseIsLeftAlone(@TempDir Path repo) throws Exception {
        write(repo, "a.json", """
                {"id":"a","name":"A","fields":[{"id":"f","label":"F","dataType":"string"}]}""");
        write(repo, "b.yml", """
                id: b
                name: B
                fields:
                  - id: f
                    label: F
                    dataType: string
                """);
        write(repo, "README.md", "name: not a form\nfields: none\n");

        assertThat(imported(repo)).extracting(Form::id).containsExactlyInAnyOrder("a", "b");
    }
}
