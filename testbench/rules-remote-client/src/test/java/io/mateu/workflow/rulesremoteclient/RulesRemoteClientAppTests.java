package io.mateu.workflow.rulesremoteclient;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.infra.out.cache.CachingRuleSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RulesRemoteClientAppTests {

    @Autowired
    RuleSource ruleSource;

    @Test
    void grpcSourceIsWiredAndCached() {
        assertThat(ruleSource).isInstanceOf(CachingRuleSource.class);
    }
}
