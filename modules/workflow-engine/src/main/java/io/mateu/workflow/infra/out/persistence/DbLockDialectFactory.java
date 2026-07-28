package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class DbLockDialectFactory {

    private final DataSource dataSource;

    @Bean
    public DbLockDialect dbLockDialect() {
        try (var con = dataSource.getConnection()) {
            return forProductName(con.getMetaData().getDatabaseProductName().toLowerCase());
        } catch (SQLException e) {
            // The database may simply not be up yet (the engine boots without it and its
            // pollers reconnect once it appears), so fall back to the JDBC url. The dialect
            // only shapes the lock SQL used later, once connections actually exist.
            var url = jdbcUrl();
            log.warn("Database unreachable while choosing the lock dialect, inferring it from "
                    + "JDBC url {} ({})", url, e.getMessage());
            return forProductName(url == null ? "" : url.toLowerCase());
        }
    }

    private String jdbcUrl() {
        return dataSource instanceof HikariDataSource hikari ? hikari.getJdbcUrl() : null;
    }

    private static DbLockDialect forProductName(String dbProduct) {
        if (dbProduct.contains("oracle")) {
            log.info("Distributed lock dialect: Oracle DBMS_LOCK");
            return new OracleDbLockDialect();
        } else if (dbProduct.contains("mariadb") || dbProduct.contains("mysql")) {
            log.info("Distributed lock dialect: MariaDB/MySQL GET_LOCK");
            return new MariaDbLockDialect();
        } else if (dbProduct.contains("h2")) {
            log.info("Distributed lock dialect: H2 in-process locks");
            return new H2DbLockDialect();
        } else {
            log.info("Distributed lock dialect: PostgreSQL advisory locks");
            return new PostgresDbLockDialect();
        }
    }
}
