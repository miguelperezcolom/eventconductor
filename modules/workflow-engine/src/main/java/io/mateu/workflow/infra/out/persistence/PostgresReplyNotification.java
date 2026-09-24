package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.sync.SyncReplyWaiters;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Cross-pod reply wake-up on PostgreSQL: {@code NOTIFY} in the reply's transaction, {@code LISTEN}
 * on one dedicated connection per pod.
 *
 * <p>Why this and not a reply topic or pod-to-pod forwarding (see SYNC-INVOCATION-PLAN §3.7):
 * PostgreSQL delivers a notification only when the transaction that raised it commits, so the notice
 * is exactly as durable as the reply it announces — no extra hop, no outbox row, no broker. The
 * payload is only the process id. It is an accelerator, not a guarantee: a notification missed
 * while the listener reconnects is caught by the waiters' poll, which is the bound on how late any
 * caller can hear.
 *
 * <p>On any other database — or without the PostgreSQL driver — both halves stay idle and the poll
 * does the job. Needs a direct connection: {@code LISTEN} does not survive a pooler in transaction
 * mode (PgBouncer), so the listener opens its own physical connection when it can.
 */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class PostgresReplyNotification implements ReplyNotifier {

    private static final String PG_CONNECTION = "org.postgresql.PGConnection";

    final JdbcTemplate jdbcTemplate;
    final DataSource dataSource;
    final DbLockDialect dbLockDialect;
    final SyncReplyWaiters waiters;

    @org.springframework.beans.factory.annotation.Value("${workflow.sync.notify.enabled:true}")
    boolean enabled = true;

    private volatile boolean active;
    private volatile boolean running = true;
    private Thread listener;
    private volatile Connection connection;

    @PostConstruct
    void start() {
        active = enabled && dbLockDialect instanceof PostgresDbLockDialect && driverPresent();
        if (!active) {
            return;
        }
        listener = new Thread(this::listen, "sync-reply-listener");
        listener.setDaemon(true);
        listener.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (listener != null) {
            listener.interrupt();
        }
        closeQuietly();
    }

    /** Whether cross-pod notification is in use on this pod. */
    public boolean isActive() {
        return active;
    }

    @Override
    public void replyRecorded(String processId) {
        if (!active) {
            return;
        }
        try {
            jdbcTemplate.execute("SELECT pg_notify(?, ?)", (java.sql.PreparedStatement statement) -> {
                statement.setString(1, CHANNEL);
                statement.setString(2, processId);
                statement.execute();
                return null;
            });
        } catch (RuntimeException e) {
            // The reply is still committed; a waiter on another pod hears on its next poll.
            log.warn("Could not NOTIFY the reply of process {}: {}", processId, e.toString());
        }
    }

    private void listen() {
        long backoff = 500;
        while (running) {
            try {
                if (connection == null || connection.isClosed()) {
                    connection = open();
                    try (var statement = connection.createStatement()) {
                        statement.execute("LISTEN " + CHANNEL);
                    }
                    log.info("Listening for synchronous replies on PostgreSQL channel {}", CHANNEL);
                    backoff = 500;
                }
                for (var processId : poll(connection, 500)) {
                    waiters.wakeFromNotification(processId);
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn("Reply listener lost its connection ({}); reconnecting in {} ms", e.toString(), backoff);
                closeQuietly();
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 10_000);
            }
        }
    }

    /** A physical connection of its own when the pool can tell us how; a pooled one otherwise. */
    private Connection open() throws Exception {
        try {
            var hikari = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
            var direct = DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
            direct.setAutoCommit(true);
            return direct;
        } catch (Exception | NoClassDefFoundError e) {
            var pooled = dataSource.getConnection();
            pooled.setAutoCommit(true);
            return pooled;
        }
    }

    /** The process ids notified since the last call, waiting up to {@code timeoutMs} for the first. */
    private static java.util.List<String> poll(Connection connection, int timeoutMs) throws Exception {
        var pgClass = Class.forName(PG_CONNECTION);
        var pg = connection.unwrap(pgClass);
        Method getNotifications = pgClass.getMethod("getNotifications", int.class);
        var notifications = (Object[]) getNotifications.invoke(pg, timeoutMs);
        if (notifications == null) {
            // Some drivers return null rather than an empty array; a cheap query keeps the socket honest.
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
            return java.util.List.of();
        }
        var ids = new java.util.ArrayList<String>(notifications.length);
        for (var notification : notifications) {
            var parameter = notification.getClass().getMethod("getParameter").invoke(notification);
            if (parameter != null) {
                ids.add(parameter.toString());
            }
        }
        return ids;
    }

    private static boolean driverPresent() {
        try {
            Class.forName(PG_CONNECTION);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void closeQuietly() {
        var current = connection;
        connection = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
                // going away anyway
            }
        }
    }
}
