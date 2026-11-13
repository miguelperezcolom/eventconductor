package io.mateu.formse2e.memory;

import io.mateu.formse2e.support.AbstractEmbeddedFormsJourney;
import io.mateu.formse2e.support.RecordingTaskExecutor;
import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.context.annotation.Bean;

/** An embedded application with forms-engine on its classpath, on memory persistence. */
@WorkflowEmbeddedApplication
public class MemoryFormsApplication {

    @Bean
    RecordingTaskExecutor recordingTaskExecutor() {
        return new RecordingTaskExecutor();
    }

    @Bean
    AbstractEmbeddedFormsJourney.TaskEventsListener taskEventsListener() {
        return new AbstractEmbeddedFormsJourney.TaskEventsListener();
    }
}
