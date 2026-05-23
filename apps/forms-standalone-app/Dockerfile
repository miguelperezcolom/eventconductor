FROM maven:3.9.5-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src /app/src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/app
COPY --from=builder /app/target/forms-standalone-app-*.jar app.jar

# ── Server ────────────────────────────────────────────────────────────────────
ENV SERVER_PORT=8080

# ── Database ──────────────────────────────────────────────────────────────────
ENV DB_URL=jdbc:postgresql://localhost:5432/workflow \
    DB_USERNAME=workflow \
    DB_PASSWORD=secret \
    DB_DRIVER=org.postgresql.Driver \
    JPA_DIALECT=org.hibernate.dialect.PostgreSQLDialect \
    DDL_AUTO=update \
    DB_POOL_SIZE=10 \
    DB_CONNECTION_TIMEOUT=20000

# ── Kafka ─────────────────────────────────────────────────────────────────────
ENV KAFKA_BROKERS=localhost:9092

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
