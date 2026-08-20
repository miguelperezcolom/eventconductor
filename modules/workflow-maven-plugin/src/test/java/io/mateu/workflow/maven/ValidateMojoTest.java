package io.mateu.workflow.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidateMojoTest {

    private final Path fixtures = new File("src/test/resources").toPath();

    private ValidateMojo mojo(String root) throws Exception {
        ValidateMojo mojo = new ValidateMojo();
        set(mojo, "workflowsDirectory", fixtures.resolve(root + "/workflows").toFile());
        set(mojo, "formsDirectory", fixtures.resolve(root + "/forms").toFile());
        set(mojo, "rulesDirectory", fixtures.resolve(root + "/rules").toFile());
        set(mojo, "validateWorkflows", true);
        set(mojo, "validateForms", true);
        set(mojo, "validateRules", true);
        set(mojo, "failOnError", true);
        set(mojo, "failOnMissing", false);
        set(mojo, "skip", false);
        return mojo;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = ValidateMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void validDefinitionsPass() throws Exception {
        assertThatCode(() -> mojo("valid").execute()).doesNotThrowAnyException();
    }

    @Test
    void invalidDefinitionsFailTheBuild() throws Exception {
        assertThatThrownBy(() -> mojo("invalid").execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("definition validation found")
                .hasMessageContaining("Duplicate step id")
                .hasMessageContaining("not a valid cron expression")
                .hasMessageContaining("must have");
    }

    @Test
    void joinOnGuardedBranchWarnsWithoutFailingTheBuild() throws Exception {
        var warnings = new java.util.ArrayList<String>();
        ValidateMojo mojo = mojo("valid");
        mojo.setLog(new org.apache.maven.plugin.logging.SystemStreamLog() {
            @Override
            public void warn(CharSequence content) {
                warnings.add(content.toString());
                super.warn(content);
            }
        });

        // failOnError is true, yet the guarded-JOIN construct only warns.
        assertThatCode(mojo::execute).doesNotThrowAnyException();

        assertThat(warnings).anySatisfy(w -> assertThat(w)
                .contains("join-on-guarded-branch.json")
                .contains("JOIN 'join' waits on guarded step 'maybe'"));
        assertThat(warnings).noneSatisfy(w -> assertThat(w).contains("join-unguarded.json"));
    }

    @Test
    void failOnErrorFalseDoesNotThrow() throws Exception {
        ValidateMojo mojo = mojo("invalid");
        set(mojo, "failOnError", false);
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void skipShortCircuits() throws Exception {
        ValidateMojo mojo = mojo("invalid");
        set(mojo, "skip", true);
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void failOnMissingReportsEmptyDirectories() throws Exception {
        ValidateMojo mojo = mojo("valid");
        set(mojo, "workflowsDirectory", fixtures.resolve("does-not-exist").toFile());
        set(mojo, "failOnMissing", true);
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    }

    @Test
    void abstractMojoLogIsAvailable() throws Exception {
        // guards against NPE on getLog() in unit context
        ValidateMojo mojo = mojo("valid");
        assertThat(mojo.getLog()).isNotNull();
    }

    /**
     * The extensions the graph editor and both IDE plugins actually write.
     *
     * <p>This is the failure that produced these three tests, and it is worth naming because of the
     * shape of it: the validator collected only {@code .json}/{@code .yaml}/{@code .yml}, so a
     * repository of {@code .ec} files was walked, nothing was found, and the build went green. A
     * validator that validates nothing looks exactly like a validator with nothing to complain
     * about.
     */
    @Test
    void definitionsWrittenByTheEditorsAreValidated() throws Exception {
        assertThatCode(() -> mojo("editor").execute()).doesNotThrowAnyException();
    }

    @Test
    void anInvalidEcFileFailsTheBuild() throws Exception {
        assertThatThrownBy(() -> mojo("editor-invalid").execute())
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("broken.ec")
                .hasMessageContaining("Duplicate step id");
    }

    /**
     * That the files are really <em>collected</em>, which passing does not prove on its own: an
     * empty file list validates just as quietly as a correct one. {@code failOnMissing} is the one
     * switch that can tell the two apart, so a directory holding nothing but {@code .ec} must not
     * trip it.
     */
    @Test
    void aDirectoryOfOnlyEcFilesCountsAsHavingDefinitions() throws Exception {
        ValidateMojo mojo = mojo("editor");
        set(mojo, "failOnMissing", true);
        set(mojo, "validateForms", true);
        set(mojo, "validateRules", true);

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    /** An .ec may hold JSON or YAML — both editors emit both — and neither may need a decision. */
    @Test
    void anEcFileParsesWhetherItHoldsYamlOrJson() throws Exception {
        ValidateMojo mojo = mojo("editor");
        set(mojo, "validateForms", false);
        set(mojo, "validateRules", false);
        set(mojo, "failOnMissing", true);

        // editor/workflows holds one of each; a parse failure is reported as a failure, not skipped.
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }
}
