package io.mateu.workflow.httpworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The HTTP egress worker: serves the built-in http-call task of HTTP_CALL steps from the http-calls topic. */
@SpringBootApplication
public class HttpWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HttpWorkerApplication.class, args);
    }
}
