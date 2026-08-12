package com.example.customapp;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.mateu.workflow.autoconfigure.WorkflowTracingAutoConfiguration;

@WorkflowEmbeddedApplication(exclude = {WorkflowTracingAutoConfiguration.class})
public class MyCustomAppWithExclusion {
}
