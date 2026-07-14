package io.mateu.workflow.rulesembeddedmvc;

import io.mateu.workflow.application.out.ProcessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class RulesMvcAppTests {

    @Autowired
    ProcessRepository processRepository;

    @Test
    void ruleStepEvaluatesAndCompletesTheProcess() {
        await().untilAsserted(() -> {
            var processes = processRepository.findAll();
            assertThat(processes).isNotEmpty();
            var process = processes.get(0);
            assertThat(process.getVariables())
                    .anyMatch(v -> "discount".equals(v.name()) && "20.0".equals(v.value()));
        });
    }
}
