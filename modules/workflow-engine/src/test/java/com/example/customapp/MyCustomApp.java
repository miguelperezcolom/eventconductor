package com.example.customapp;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.stereotype.Component;

@WorkflowEmbeddedApplication
public class MyCustomApp {

    @Component
    public static class MyCustomUserComponent {
    }
}
