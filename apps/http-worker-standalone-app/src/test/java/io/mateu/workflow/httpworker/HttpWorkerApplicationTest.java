package io.mateu.workflow.httpworker;

import io.mateu.workflow.worker.api.TaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.main.web-application-type=none")
class HttpWorkerApplicationTest {

    @Autowired TaskRegistry registry;

    @Test
    void theHttpCallTaskIsServed() {
        assertThat(registry.refs()).contains("http-call@1");
    }
}
