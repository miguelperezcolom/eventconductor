package io.mateu.workflow.application.out;

import java.util.List;

/**
 * The set of shards currently accepting new processes. The ingress router reads it to place each new
 * process; draining a shard is removing it from this list (its in-flight work finishes where it is,
 * new work stops arriving). This is the piece that has to be updatable <b>hot</b> for elastic
 * add/remove — a config-backed implementation is enough to start; a later increment makes it watch a
 * ConfigMap or a compacted topic so scale events take effect without a restart.
 */
public interface ShardRegistry {

    /** Shard ids currently {@code active} (accepting new processes). Empty when not sharded. */
    List<String> activeShards();
}
