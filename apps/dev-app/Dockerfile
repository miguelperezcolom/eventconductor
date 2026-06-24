FROM maven:3.9.5-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src /app/src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runner
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /opt/app
COPY --from=builder --chown=app:app /app/target/app-*.jar app.jar

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
