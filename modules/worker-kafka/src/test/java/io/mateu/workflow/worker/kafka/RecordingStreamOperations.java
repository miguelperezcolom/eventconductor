package io.mateu.workflow.worker.kafka;

import java.util.ArrayList;
import java.util.List;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.util.MimeType;

/** A {@link StreamOperations} that records what was sent (or is programmed to refuse it). */
class RecordingStreamOperations implements StreamOperations {

    record Sent(String binding, Object payload) {}

    final List<Sent> sent = new ArrayList<>();
    boolean accept = true;

    @Override
    public boolean send(String bindingName, Object data) {
        sent.add(new Sent(bindingName, data));
        return accept;
    }

    @Override
    public boolean send(String bindingName, Object data, MimeType contentType) {
        return send(bindingName, data);
    }

    @Override
    public boolean send(String channelName, String bindingName, Object data) {
        return send(bindingName, data);
    }

    @Override
    public boolean send(String channelName, String bindingName, Object data, MimeType contentType) {
        return send(bindingName, data);
    }
}
