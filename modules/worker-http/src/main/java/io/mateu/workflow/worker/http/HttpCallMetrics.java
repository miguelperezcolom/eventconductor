package io.mateu.workflow.worker.http;

/** Call counts and latency per connection (or host) and status; Micrometer when present. */
public interface HttpCallMetrics {

    HttpCallMetrics NONE = (target, status, nanos) -> { };

    void call(String target, String status, long nanos);
}
