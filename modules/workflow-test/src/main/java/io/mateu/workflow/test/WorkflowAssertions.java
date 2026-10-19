package io.mateu.workflow.test;

/** Entry point for the kit's assertions: {@code import static io.mateu.workflow.test.WorkflowAssertions.assertThat;}. */
public final class WorkflowAssertions {

    private WorkflowAssertions() {
    }

    public static ProcessAssert assertThat(TestProcess process) {
        return new ProcessAssert(process);
    }
}
