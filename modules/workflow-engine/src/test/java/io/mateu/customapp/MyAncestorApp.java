package io.mateu.customapp;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.stereotype.Component;

@WorkflowEmbeddedApplication
public class MyAncestorApp {

    @Component
    public static class MyAncestorUserComponent {
    }
}
