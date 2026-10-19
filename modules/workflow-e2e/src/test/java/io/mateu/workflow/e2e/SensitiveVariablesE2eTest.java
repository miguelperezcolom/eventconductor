package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.SensitiveVariableMasking;
import io.mateu.workflow.application.services.SensitiveVariableRevealService;
import io.mateu.workflow.application.usecases.process.operate.SetProcessVariablesCommand;
import io.mateu.workflow.application.usecases.process.operate.SetProcessVariablesUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sensitive variables in the embedded engine (memory): the embedded worker and the guards work on
 * plaintext — the engine's own trust boundary — while everything a person reads (the views, the audit
 * trail, the process log) shows the mask, and reading a value is an explicit, audited reveal.
 */
@TestPropertySource(properties = "workflow.security.sensitive.reveal-roles=pci-auditor")
class SensitiveVariablesE2eTest extends AbstractE2eTest {

    static io.mateu.workflow.dtos.Variable dto(String name, String value) {
        return new io.mateu.workflow.dtos.Variable(name, value);
    }

    static final String CARD = "4111111111111111";

    @Autowired SetProcessVariablesUseCase setProcessVariables;
    @Autowired SensitiveVariableRevealService reveal;

    @Test
    void theEmbeddedWorkerAndTheGuardsSeePlaintextAndTheViewsTheMask() {
        worker.on("charge", TestWorker.succeed(var("authCode", "A-77")));

        createProcess("sensitive-payment", "pay-1", dto("cardNumber", CARD), dto("amount", "120"));

        assertThat(process("pay-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        // In process: the embedded worker got the card number as it is.
        assertThat(worker.received().getFirst().variables()).contains(dto("cardNumber", CARD));
        // The guard on visa read it (cardNumber.startsWith('4')) and let the flow through.
        assertThat(worker.invocationsOf("visa")).isEqualTo(1);
        // What the console, REST and MCP show.
        assertThat(SensitiveVariableMasking.asMap(process("pay-1")))
                .containsEntry("cardNumber", "••••").containsEntry("authCode", "••••").containsEntry("amount", "120");
    }

    @Test
    void aWorkerFailureQuotingTheCardNeverReachesTheProcessLogInClear() {
        worker.on("charge", TestWorker.failWith("Card " + CARD + " was declined"));

        createProcess("sensitive-payment", "pay-2", dto("cardNumber", CARD));

        assertThat(errorsOf("pay-2")).isNotEmpty().noneMatch(line -> line.contains(CARD))
                .anyMatch(line -> line.contains("Card •••• was declined"));
    }

    @Test
    void anOperatorsChangeIsAuditedWithoutTheValueAndARevealIsAuditedWithTheReason() {
        worker.on("charge", TestWorker.deferForever());
        createProcess("sensitive-payment", "pay-3", dto("cardNumber", CARD), dto("amount", "120"));
        var processId = process("pay-3").getId();

        setProcessVariables.handle(new SetProcessVariablesCommand(processId,
                List.of(new io.mateu.workflow.domain.aggregates.Variable("cardNumber", "4000000000000002"),
                        new io.mateu.workflow.domain.aggregates.Variable("amount", "130")),
                List.of(), "customer replaced the card", "bob"));

        assertThat(logsOf("pay-3")).anyMatch(line -> line.contains("set cardNumber = •••• (was ••••)")
                && line.contains("set amount = '130' (was '120')"));
        assertThat(logsOf("pay-3")).noneMatch(line -> line.contains(CARD) || line.contains("4000000000000002"));

        assertThatThrownBy(() -> reveal.reveal(processId, "cardNumber", "curious",
                new AuthorizationContext("bob", List.of(), List.of("operator"))))
                .isInstanceOf(FlowAuthorizationDeniedException.class);
        assertThat(reveal.reveal(processId, "cardNumber", "chargeback CB-9",
                new AuthorizationContext("alice", List.of(), List.of("pci-auditor")))).isEqualTo("4000000000000002");
        assertThat(logsOf("pay-3"))
                .anyMatch(line -> line.contains("[reveal-variable] by bob NOT carried out"))
                .anyMatch(line -> line.contains("[reveal-variable] by alice: cardNumber — reason: chargeback CB-9"));
    }
}
