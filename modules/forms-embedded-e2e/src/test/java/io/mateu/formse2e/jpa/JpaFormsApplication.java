package io.mateu.formse2e.jpa;

import io.mateu.formse2e.support.AbstractEmbeddedFormsJourney;
import io.mateu.formse2e.support.RecordingTaskExecutor;
import io.mateu.workflow.autoconfigure.WorkflowEmbeddedApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** An embedded application with forms-engine on its classpath, on JPA persistence (engine and forms). */
@WorkflowEmbeddedApplication
@EntityScan("io.mateu.workflow")
@EnableJpaRepositories("io.mateu.workflow")
public class JpaFormsApplication {

    @Bean
    RecordingTaskExecutor recordingTaskExecutor() {
        return new RecordingTaskExecutor();
    }

    @Bean
    AbstractEmbeddedFormsJourney.TaskEventsListener taskEventsListener() {
        return new AbstractEmbeddedFormsJourney.TaskEventsListener();
    }
}
