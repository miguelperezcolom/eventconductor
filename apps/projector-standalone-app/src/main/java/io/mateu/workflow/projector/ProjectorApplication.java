package io.mateu.workflow.projector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The standalone CQRS projector: it consumes the shared {@code process-index} topic that every shard
 * produces to, and maintains one fleet-wide process-index read model in a read database of its own.
 *
 * <p>It exists because a sharded write side has no single database that can answer "what is running
 * across the fleet". The in-process projector each shard runs indexes only that shard's processes;
 * this one indexes all of them, and stamps each row with the shard the process lives on (which rides
 * the event, stamped on the owning shard) so a targeted command can be routed back.
 *
 * <p><b>It does not depend on the engine.</b> {@code ProcessStatusChanged} carries the whole projected
 * shape precisely so a projector needs no access to the write side — no entities, no write schema, no
 * engine beans. What that buys is a service small enough to scale, restart and rebuild on its own.
 *
 * <p>Base package {@code io.mateu.workflow.projector} rather than {@code io.mateu.workflow}: component
 * scanning must not reach the read model's Spring-annotated adapters (the in-heap one would otherwise
 * register itself and shadow the real store). The one bean that matters is declared explicitly, in
 * {@link ProjectorConfiguration}.
 *
 * <p><b>Stateless.</b> Its state is its consumer-group offsets; the index it writes is derived and
 * disposable — recreate the schema, replay the compacted topic from the earliest offset, and it comes
 * back. Scale it inside its group: the topic is keyed by {@code processId}, so partitions split and
 * per-process ordering holds however many instances run.
 */
@SpringBootApplication
public class ProjectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectorApplication.class, args);
    }
}
