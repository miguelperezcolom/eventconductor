FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /opt/app
ARG JAR=target/app.jar
COPY --chown=app:app ${JAR} app.jar
USER app
ENTRYPOINT ["java","-jar","app.jar"]
