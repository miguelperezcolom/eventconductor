package io.mateu.workflow.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Runtime-side properties. rules.source selects where the runtime reads rules
 * from: local (a same-JVM catalog bean), classpath, rest or grpc.
 */
@ConfigurationProperties(prefix = "rules")
public class RulesRuntimeProperties {

    private String source = "local";
    private Catalog catalog = new Catalog();
    private Cache cache = new Cache();
    private boolean kafkaRefresh = false;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Catalog getCatalog() {
        return catalog;
    }

    public void setCatalog(Catalog catalog) {
        this.catalog = catalog;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public boolean isKafkaRefresh() {
        return kafkaRefresh;
    }

    public void setKafkaRefresh(boolean kafkaRefresh) {
        this.kafkaRefresh = kafkaRefresh;
    }

    public static class Catalog {
        /** Base URL of the catalog's REST API, e.g. http://localhost:8991 */
        private String url;
        /** host:port of the catalog's gRPC server, e.g. localhost:9090 */
        private String grpcTarget;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getGrpcTarget() {
            return grpcTarget;
        }

        public void setGrpcTarget(String grpcTarget) {
            this.grpcTarget = grpcTarget;
        }
    }

    public static class Cache {
        /** Time-to-live of the rule cache; PT0S means entries never expire. */
        private Duration ttl = Duration.ofMinutes(5);

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }
}
