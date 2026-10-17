package io.mateu.workflow.processindex;

import io.mateu.workflow.application.readmodel.MessageSubscription;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A read database that is down, or has no schema: the stores still construct (startup does not fail
 * over it), fall back to their portable statements, and every read or write fails loudly rather than
 * answering "nothing there".
 */
class JdbcStoresFailureTest {

    /** A data source whose database is not up. */
    static final DataSource DOWN = new DataSource() {
        @Override public Connection getConnection() throws SQLException { throw new SQLException("connection refused"); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    };

    /** A database that is up but has no read-model tables. */
    static DataSource noSchema() {
        return new DriverManagerDataSource("jdbc:h2:mem:noschema-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
    }

    static final ProcessIndexRow ROW = new ProcessIndexRow("p1", "order-1", "Order", "orders", 1, "RUNNING", 0,
            null, null, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);

    @Test
    void theIndexStoreConstructsOnADownDatabaseAndFailsEveryCallLoudly() {
        var store = new JdbcProcessIndexStore(DOWN);
        assertThatThrownBy(() -> store.upsert(ROW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Process-index read store failed");
        assertThatThrownBy(() -> store.findByProcessId("p1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::countByStatus).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.search("x", false, 0, 10)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theIndexStoreFailsLoudlyWithoutItsSchema() {
        var store = new JdbcProcessIndexStore(noSchema());
        assertThatThrownBy(() -> store.findByStatusIn(List.of("RUNNING"))).isInstanceOf(IllegalStateException.class);
        // An empty filter is answered without touching the database.
        assertThat(store.findByWorkflowDefinitionIdAndStatusIn("orders", List.of())).isEmpty();
    }

    @Test
    void theSubscriptionStoreFailsLoudlyAndAnIncompleteKeyRoutesNowhere() {
        var down = new JdbcMessageSubscriptionStore(DOWN);
        assertThatThrownBy(() -> down.subscribe(new MessageSubscription("step-1", "paid", "o-1", "s0",
                LocalDateTime.now()))).isInstanceOf(IllegalStateException.class).hasMessageContaining("step-1");
        assertThatThrownBy(() -> down.unsubscribe("step-1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("step-1");
        assertThatThrownBy(() -> down.shardsWaitingFor("paid", "o-1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paid");
        // Without a name or key there is nothing to correlate: no query, no shards.
        assertThat(down.shardsWaitingFor(null, "o-1")).isEmpty();
        assertThat(down.shardsWaitingFor("paid", null)).isEmpty();
    }

    @Test
    void thePlacementStoreFailsLoudlyOnReadAndAnswersNothingForABlankKey() {
        var down = new JdbcProcessPlacementStore(DOWN);
        assertThatThrownBy(() -> down.find("order-1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order-1");
        assertThatThrownBy(() -> down.claim("order-1", "s0")).isInstanceOf(IllegalStateException.class);
        assertThat(down.find(null)).isEmpty();
        assertThat(down.find("  ")).isEmpty();
    }
}
