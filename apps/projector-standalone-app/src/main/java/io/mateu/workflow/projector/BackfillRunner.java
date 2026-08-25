package io.mateu.workflow.projector;

import com.zaxxer.hikari.HikariDataSource;
import io.mateu.workflow.application.out.ProcessIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

/**
 * Runs {@link ShardBackfill} across a fleet and exits — the projector image doubling as the cutover
 * job, so the operator who has the shard list runs one container rather than assembling one.
 *
 * <pre>
 * java -jar app.jar \
 *   --backfill.shards=0,1,2 \
 *   --backfill.jdbc.url='jdbc:postgresql://postgres-{shard}.ec-shard.svc.cluster.local:5432/eventconductor'
 * </pre>
 *
 * <p>Present only when {@code backfill.shards} is set, so the ordinary projector deployment — the same
 * image, the same jar — never runs it. When it is set the application does the backfill and stops:
 * this is a Job, not a service, and a Job that keeps consuming a topic afterwards never completes.
 */
@Component
@ConditionalOnProperty(name = "backfill.shards")
public class BackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);

    private final ProcessIndexRepository index;
    private final DataSource readDatabase;
    private final ApplicationContext context;

    /** The shard ids to read, in any order — the backfill is idempotent and order-independent. */
    @Value("${backfill.shards}")
    private String shards;

    /** The shards' JDBC url, with {@code {shard}} standing in for each id. */
    @Value("${backfill.jdbc.url}")
    private String jdbcUrlTemplate;

    @Value("${backfill.jdbc.username:eventconductor}")
    private String username;

    @Value("${backfill.jdbc.password:eventconductor}")
    private String password;

    public BackfillRunner(ProcessIndexRepository index, DataSource readDatabase,
                          ApplicationContext context) {
        this.index = index;
        this.readDatabase = readDatabase;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> shardIds = Arrays.stream(shards.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
        var backfill = new ShardBackfill(index, readDatabase);
        var total = 0;
        for (var shardId : shardIds) {
            var url = jdbcUrlTemplate.replace("{shard}", shardId);
            log.info("Backfilling shard {} from {}", shardId, url);
            try (var shard = pool(url)) {
                total += backfill.backfill(shard, shardId);
            }
        }
        log.info("Backfill complete: {} process(es) from {} shard(s)", total, shardIds.size());
        // A Job has to finish. Exiting here rather than letting the context linger is also what keeps
        // the projector's consumer from starting a second, competing member of its group.
        System.exit(org.springframework.boot.SpringApplication.exit(context, () -> 0));
    }

    private HikariDataSource pool(String url) {
        var hikari = new HikariDataSource();
        hikari.setPoolName("backfill-source");
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        // One reader; the backfill is a single sequential scan per shard.
        hikari.setMaximumPoolSize(1);
        hikari.setReadOnly(true);
        return hikari;
    }
}
