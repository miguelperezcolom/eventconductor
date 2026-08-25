package com.example.myapp;

import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import org.springframework.context.annotation.Bean;

@WorkflowEmbeddedApplication
public class MyTestApp {

    @Bean
    public EmbeddedTaskExecutor embeddedTaskExecutor() {
        return request -> {};
    }
}
