package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.crypto.SensitiveNames;
import io.mateu.workflow.crypto.VariableEnvelope;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.mateu.workflow.application.services.SensitiveFixtures.CARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Policy resolution, sealing at the boundaries, masking and log redaction. */
class SensitiveVariableProtectionTest {

    final io.mateu.workflow.crypto.VariableEncryption keys = SensitiveFixtures.keys();
    final ProcessRepository processes = mock(ProcessRepository.class);
    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final SensitiveVariablePolicies policies = SensitiveFixtures.policies(keys, processes, definitions);
    final VariableProtection protection = new VariableProtection(policies);

    @Test
    void aProcessIsProtectedByWhatItsDefinitionVersionDeclares() {
        var process = SensitiveFixtures.process("p1");
        var names = policies.of(process);
        assertThat(names.test("cardNumber")).isTrue();
        assertThat(names.test("patient")).isTrue();
        assertThat(names.test("authCode")).isTrue();
        assertThat(names.test("amount")).isFalse();
        // The engine's rendered requests embed values: sensitive whenever anything is.
        assertThat(names.test("__http")).isTrue();
        assertThat(names.test("__llm")).isTrue();
        // Registered: a step of the process resolves without loading it.
        assertThat(policies.ofProcessId("p1")).isEqualTo(names);
        assertThat(policies.ofProcessId(null)).isSameAs(SensitiveNames.NONE);
        assertThat(SensitiveVariablePolicies.ofSnapshot("x", 1, "{\"steps\":[]}")).isSameAs(SensitiveNames.NONE);
        assertThat(SensitiveVariablePolicies.ofSnapshot("x", 1, "{\"sensitiveVariables\": not json"))
                .satisfies(all -> assertThat(all.test("anything")).isTrue());
        assertThat(policies.enabled()).isTrue();
    }

    @Test
    void anUnknownProcessIsLookedUpAndADefinitionOrTheUnionStandsInWhenThereIsNone() {
        var process = SensitiveFixtures.process("p2");
        when(processes.findById("p2")).thenReturn(Optional.of(process));
        assertThat(policies.ofProcessId("p2").test("cardNumber")).isTrue();
        when(definitions.findById("payment")).thenReturn(Optional.of(SensitiveFixtures.definition()));
        when(definitions.findAll()).thenReturn(List.of(SensitiveFixtures.definition()));
        assertThat(policies.ofDefinitionId("payment").test("cardNumber")).isTrue();
        assertThat(policies.ofDefinitionId("payment").test("cardNumber")).isTrue(); // cached
        assertThat(policies.global().test("patient.ssn")).isTrue();
        policies.definitionsChanged();
        assertThat(policies.ofDefinitionId(null).test("cardNumber")).isTrue();
        assertThat(SensitiveVariablePolicies.none().ofProcessId("p2")).isSameAs(SensitiveNames.NONE);
        assertThat(SensitiveVariablePolicies.none().global().isEmpty()).isTrue();
    }

    @Test
    void aRowStoresSensitiveValuesSealedAndReadsThemBackInClear() {
        var process = SensitiveFixtures.process("p3");
        var names = policies.of(process);
        var json = protection.variablesJson(process.getVariables(), names);
        assertThat(json).doesNotContain(CARD).doesNotContain("123-45-6789").contains("\"amount\"").contains("120")
                .contains("\"cardNumber\"").contains(VariableEnvelope.PREFIX);
        assertThat(protection.variablesFromJson(json)).containsExactlyElementsOf(process.getVariables());
        // Nothing declared, nothing sealed.
        assertThat(protection.variablesJson(process.getVariables(), SensitiveNames.NONE)).contains(CARD);
        // Blobs (a reply, a forEach scope) are sealed whole.
        var blob = protection.sealBlob("{\"cardNumber\":\"" + CARD + "\"}", names);
        assertThat(blob).startsWith(VariableEnvelope.PREFIX);
        assertThat(protection.openBlob(blob)).contains(CARD);
        assertThat(protection.sealBlob("x", SensitiveNames.NONE)).isEqualTo("x");
        // Without keys a sensitive variable is never written in clear: the write fails closed.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VariableProtection.none().variablesJson(process.getVariables(), names))
                .isInstanceOf(io.mateu.workflow.crypto.EncryptionConfigurationException.class)
                .hasMessageContaining("Refusing to store sensitive variables");
    }

    @Test
    void eventsLeavingTheEngineCarryTheirSensitiveValuesSealed() {
        policies.of(SensitiveFixtures.process("p4"));
        var task = new TaskExecutionRequested("se", "p4", "payment", "charge", "charge@1", List.of(
                new io.mateu.workflow.dtos.Variable("cardNumber", CARD),
                new io.mateu.workflow.dtos.Variable("amount", "120")));
        var sealed = protection.sealEvent(task);
        assertThat(sealed.variables().get(0).value()).startsWith(VariableEnvelope.PREFIX);
        assertThat(sealed.variables().get(1).value()).isEqualTo("120");
        assertThat(protection.openEvent(sealed)).isEqualTo(task);
        assertThat(protection.openEvent(task)).isSameAs(task);
        // A creation request goes by its definition; a message by every definition's union.
        when(definitions.findById("payment")).thenReturn(Optional.of(SensitiveFixtures.definition()));
        when(definitions.findAll()).thenReturn(List.of(SensitiveFixtures.definition()));
        var creation = protection.sealEvent(new ProcessCreationRequested("payment", "bk",
                List.of(new io.mateu.workflow.dtos.Variable("cardNumber", CARD))));
        assertThat(creation.variables().getFirst().value()).startsWith(VariableEnvelope.PREFIX);
        var message = protection.sealEvent(new MessageReceived("paid", "k",
                List.of(new io.mateu.workflow.dtos.Variable("authCode", "A-1"))));
        assertThat(message.variables().getFirst().value()).startsWith(VariableEnvelope.PREFIX);
        // An outbox payload, likewise.
        var payload = io.mateu.core.infra.JsonSerializer.toJson(task);
        var sealedPayload = protection.sealPayload(payload, policies.ofProcessId("p4"));
        assertThat(sealedPayload).doesNotContain(CARD);
        assertThat(protection.openPayload(sealedPayload)).contains(CARD);
        // Disabled: nothing is touched.
        assertThat(VariableProtection.none().sealEvent(task)).isSameAs(task);
    }

    @Test
    void aLoggedEventNeverPrintsASensitiveValue() {
        policies.of(SensitiveFixtures.process("p5"));
        var task = new TaskExecutionRequested("se", "p5", "payment", "charge", "charge@1", List.of(
                new io.mateu.workflow.dtos.Variable("cardNumber", CARD),
                new io.mateu.workflow.dtos.Variable("amount", "120")));
        assertThat(protection.describe(task)).startsWith("TaskExecutionRequested").doesNotContain(CARD)
                .contains("••••").contains("120");
        assertThat(protection.describe(protection.sealEvent(task))).doesNotContain(VariableEnvelope.PREFIX);
        assertThat(VariableProtection.none().describe(task)).isEqualTo(task.toString());
        assertThat(protection.describe(null)).isEqualTo("null");
    }

    @Test
    void theConsoleRestAndMcpShowTheMask() {
        var process = SensitiveFixtures.process("p6");
        assertThat(SensitiveVariableMasking.asMap(process))
                .containsEntry("cardNumber", "••••").containsEntry("patient", "••••").containsEntry("amount", "120");
        assertThat(SensitiveVariableMasking.isSensitive(process, "cardNumber")).isTrue();
        assertThat(SensitiveVariableMasking.shown("x", keys.seal("y"), SensitiveNames.NONE)).isEqualTo("••••");
        assertThat(SensitiveVariableMasking.shown("x", null, SensitiveNames.NONE)).isNull();
        var names = SensitiveVariableMasking.namesOf(process);
        assertThat(SensitiveVariableMasking.json("{\"cardNumber\":\"" + CARD + "\",\"ok\":true}", names))
                .doesNotContain(CARD).contains("\"ok\":true");
        assertThat(SensitiveVariableMasking.json("[1]", names)).isEqualTo("••••");
        assertThat(SensitiveVariableMasking.json("not json", names)).isEqualTo("••••");
        assertThat(SensitiveVariableMasking.json("{}", SensitiveNames.NONE)).isEqualTo("{}");
        assertThat(SensitiveVariableMasking.variables(null)).isEmpty();
        assertThat(SensitiveVariableMasking.namesOf(null)).isSameAs(SensitiveNames.NONE);
    }

    @Test
    void aLogLineNeverStoresACurrentSensitiveValue() {
        var process = SensitiveFixtures.process("p7");
        policies.of(process);
        when(processes.findById("p7")).thenReturn(Optional.of(process));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("processes", processes);
        var redaction = new SensitiveLogRedaction(policies, beans.getBeanProvider(ProcessRepository.class));
        var line = new LogMessage("l1", null, "p7", null, "Error", "Card " + CARD + " declined for 120 — "
                + keys.seal("x"), "worker");
        var redacted = redaction.redact(line);
        assertThat(redacted.getMessage()).isEqualTo("Card •••• declined for 120 — ••••");
        var harmless = line.withMessage("nothing here");
        assertThat(redaction.redact(harmless)).isSameAs(harmless);
        assertThat(redaction.redact("other", "Card " + CARD)).isEqualTo("Card " + CARD);
        assertThat(redaction.redact((LogMessage) null)).isNull();
    }

    @Test
    void anLlmPromptDoesNotSeeSensitiveVariablesUnlessTheStepOptsIn() throws Exception {
        var yaml = SensitiveFixtures.DEFINITION + """
                  - id: summarize
                    type: LLM_CALL
                    name: Summarize
                    preconditionStepId: start
                    llm:
                      connection: claude
                      prompt: "Amount ${amount}; card ${cardNumber}"
                      allowSensitiveVariables: true
                      outputVariable: summary
                """;
        var definition = SensitiveFixtures.definition(yaml);
        var process = io.mateu.workflow.domain.aggregates.Process.create("p8", definition, "bk",
                List.of(new Variable("cardNumber", CARD), new Variable("amount", "120")));
        var step = definition.steps().stream().filter(s -> "summarize".equals(s.id())).findFirst().orElseThrow();
        assertThat(io.mateu.workflow.domain.services.LlmRequestRenderer.render(step, process, "se")).contains(CARD);
        // Without the opt-in the variable is not there to render (a prompt naming it is refused at load).
        var notAllowed = step.withLlm(new io.mateu.workflow.domain.aggregates.LlmCall("claude", null, null,
                "Amount ${amount}; card ${cardNumber == null ? 'withheld' : cardNumber}", null, null, null, null, null, null));
        assertThat(io.mateu.workflow.domain.services.LlmRequestRenderer.render(notAllowed, process, "se"))
                .doesNotContain(CARD).contains("withheld").contains("120");
    }
}
