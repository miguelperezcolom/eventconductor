package io.mateu.workflow.infra.out.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class DbLockDialectFactory {

    private final DataSource dataSource;

    @Bean
    public DbLockDialect dbLockDialect() throws Exception {
        try (var con = dataSource.getConnection()) {
            String dbProduct = con.getMetaData().getDatabaseProductName().toLowerCase();
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
}
