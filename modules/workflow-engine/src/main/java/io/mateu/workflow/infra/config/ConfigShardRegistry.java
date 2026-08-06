package io.mateu.workflow.infra.config;

import io.mateu.workflow.application.out.ShardRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Static shard registry from {@code workflow.sharding.active-shards} (a comma-separated list of shard
 * ids). The starting point: a scale event is a config change + a redeploy. A later increment replaces
 * this with a hot-reloadable source (watched ConfigMap / compacted topic) behind the same port, and
 * nothing that reads {@link ShardRegistry} changes. Empty (the default) means not sharded.
 */
@Component
public class ConfigShardRegistry implements ShardRegistry {

    private final List<String> activeShards;

    public ConfigShardRegistry(@Value("${workflow.sharding.active-shards:}") String activeShardsCsv) {
        this.activeShards = (activeShardsCsv == null || activeShardsCsv.isBlank())
                ? List.of()
                : Arrays.stream(activeShardsCsv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
    }

    @Override
    public List<String> activeShards() {
        return activeShards;
    }
}
