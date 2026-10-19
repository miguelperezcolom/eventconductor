package io.mateu.workflow.application.services;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

/**
 * A line on the process timeline about one of its tasks — claimed by, released by, reassigned to.
 * Best effort: losing a line of history must never be why a claim or a release fails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskTimeline {

    final StreamBridge streamBridge;

    public void log(String stepExecutionId, String message) {
        if (stepExecutionId == null) {
            return;
        }
        try {
            streamBridge.send("upstream", new TaskLogEmitted(stepExecutionId, MessageType.Info, message));
        } catch (RuntimeException e) {
            log.warn("Could not record '{}' on the timeline of step execution {}", message, stepExecutionId, e);
        }
    }
}
