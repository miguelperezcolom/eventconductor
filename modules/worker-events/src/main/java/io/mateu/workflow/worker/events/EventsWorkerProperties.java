package io.mateu.workflow.worker.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The publish-event worker.
 *
 * <pre>
 *   workflow:
 *     events:
 *       destinations:                  # the same map the engine validates PUBLISH_EVENT steps against
 *         bookings: { topic: booking-events }
 *         audit:    { topic: audit-events, format: plain }
 *       worker:
 *         enabled: true                # false: this deployment does not serve publish-event tasks
 *         topic: event-publications    # where the engine dispatches them
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.events")
public class EventsWorkerProperties {

    /** Logical destination names mapped to topics (kafka mode). */
    private Map<String, Destination> destinations = new LinkedHashMap<>();

    private Worker worker = new Worker();

    public Map<String, Destination> getDestinations() {
        return destinations;
    }

    public void setDestinations(Map<String, Destination> destinations) {
        this.destinations = destinations;
    }

    public Worker getWorker() {
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker;
    }

    public static class Worker {
        /** Whether this deployment serves publish-event tasks. */
        private boolean enabled = true;
        /** The topic the engine dispatches them to. */
        private String topic = "event-publications";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }
    }

    public static class Destination {
        /** The topic the destination is published to. */
        private String topic;
        /** {@code binary} (default), {@code structured} or {@code plain}; a step may override. */
        private String format;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }
    }
}
