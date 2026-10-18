package io.mateu.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// io.mateu.sample too: the sample worker's handler (GreetHandler) lives there, and the generated
// GreetingsTasksAutoConfiguration needs it as a bean. Until Mateu 3.0-alpha.409 Mateu's own scan of
// all of io.mateu found it.
@SpringBootApplication(scanBasePackages = {"io.mateu.workflow", "io.mateu.sample"})
public class DevApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevApplication.class, args);
    }

}
