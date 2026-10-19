package io.mateu.workflow.infra.in.mcp;

import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.SensitiveFixtures;
import io.mateu.workflow.application.services.SensitiveVariableRevealService;
import io.mateu.workflow.application.usecases.process.operate.OperatorAudit;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static io.mateu.workflow.application.services.SensitiveFixtures.CARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensitiveVariableMcpToolsTest {

    final ProcessOperationsService processes = mock(ProcessOperationsService.class);
    final OperatorAudit audit = mock(OperatorAudit.class);
    final SensitiveVariableRevealService reveal = new SensitiveVariableRevealService(processes, audit, List.of("pci-auditor"));

    SensitiveVariableMcpToolsTest() {
        when(processes.get(eq("p1"), any())).thenReturn(SensitiveFixtures.process("p1"));
    }

    @Test
    void theMcpToolRevealsTheSameWayAndSaysWhyWhenItDoesNot() {
        var tools = new SensitiveVariableMcpTools(reveal);
        assertThat(tools.getSystemContext()).contains("revealProcessVariable");
        assertThat(tools.revealProcessVariable("p1", "cardNumber", "x")).startsWith("Refused:");
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) () -> new AuthorizationContext("alice", List.of(), List.of("pci-auditor")));
        tools.callerResolver = beans.getBeanProvider(CallerResolver.class);
        assertThat(tools.revealProcessVariable("p1", "cardNumber", "audit")).isEqualTo("cardNumber=" + CARD);
        assertThat(new SensitiveVariableRevealService(processes, audit, null).mayReveal(
                new AuthorizationContext("alice", List.of(), List.of("pci-auditor")))).isFalse();
    }
}
