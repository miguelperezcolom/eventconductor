package io.mateu.workflowdist.support;

import io.mateu.workflow.application.sync.SyncInvocationService;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Map;

/** Synchronous invocations against one pod, through its service — what the HTTP endpoint does. */
public final class SyncCalls {

    private SyncCalls() {
    }

    public static SyncInvocationService.Started start(ConfigurableApplicationContext pod, String definition,
                                                      String key, Map<String, String> variables, Duration wait) {
        return pod.getBean(SyncInvocationService.class).start(new SyncInvocationService.StartRequest(
                definition, key, null, variables, wait, null));
    }

    /** Starts and waits: the view the caller would be answered with. */
    public static SyncInvocationService.View invoke(ConfigurableApplicationContext pod, String definition,
                                                    String key, Map<String, String> variables, Duration wait) {
        var started = start(pod, definition, key, variables, wait);
        return pod.getBean(SyncInvocationService.class).await(started.invocation(), started.timeToWait()).join();
    }

    /** What a caller reading by key gets now. */
    public static SyncInvocationService.View byKey(ConfigurableApplicationContext pod, String definition, String key) {
        var service = pod.getBean(SyncInvocationService.class);
        return service.view(service.findByKey(definition, key).orElseThrow());
    }
}
