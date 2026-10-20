package io.mateu.workflow.application.services;

import io.mateu.workflow.crypto.VariableEncryption;
import io.mateu.workflow.infra.out.persistence.WorkflowDefinitionVersionEntityRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The DSL side: declaration, validation at load, the version hash, and the refusal without keys. */
class SensitiveVariablesDefinitionTest {

    @Test
    void aDefinitionDeclaresVariablesAndStepOutputsAsSensitive() {
        var definition = SensitiveFixtures.definition();
        assertThat(definition.sensitiveVariables()).containsExactly("cardNumber", "patient.*");
        assertThat(definition.sensitiveVariablePatterns()).containsExactly("cardNumber", "patient.*", "authCode");
        assertThat(definition.declaresSensitiveVariables()).isTrue();
        definition.checkInvariants();
        // Carried through every copy the engine makes.
        assertThat(definition.withMaxSteps(5).withTags(java.util.List.of("x")).sensitiveVariables())
                .containsExactly("cardNumber", "patient.*");
        assertThat(definition.withSensitiveVariables(null).sensitiveVariablePatterns()).containsExactly("authCode");
    }

    @Test
    void malformedDeclarationsAreRefusedAtLoad() {
        var bad = SensitiveFixtures.definition(SensitiveFixtures.DEFINITION.replace("[cardNumber, \"patient.*\"]", "[\"card number\"]"));
        assertThatThrownBy(bad::checkInvariants).hasMessageContaining("is not a variable name or a glob");
        var noOutputs = SensitiveFixtures.definition(SensitiveFixtures.DEFINITION.replace("outputVariables: [authCode]", ""));
        assertThatThrownBy(noOutputs::checkInvariants).hasMessageContaining("declares no outputVariables");
    }

    @Test
    void aSearchAttributeOverASensitiveVariableIsRefused() {
        var definition = SensitiveFixtures.definition(SensitiveFixtures.DEFINITION.replace("steps:",
                "searchAttributes:\n  card: \"${cardNumber}\"\n  amount: { value: \"${amount}\", type: number }\nsteps:"));
        assertThatThrownBy(definition::checkInvariants)
                .hasMessageContaining("searchAttributes.card reads the sensitive variable(s) cardNumber");
    }

    @Test
    void aCorrelationOrLockKeyReadingASensitiveVariableIsRefused() {
        var wait = SensitiveFixtures.definition(SensitiveFixtures.DEFINITION + """
                  - id: paid
                    type: WAIT_FOR_MESSAGE
                    name: Paid
                    preconditionStepId: start
                    messageName: paid
                    correlationExpression: cardNumber
                """);
        assertThatThrownBy(wait::checkInvariants).hasMessageContaining("step 'paid' correlationExpression reads the sensitive variable(s) cardNumber");
        var lock = SensitiveFixtures.definition(SensitiveFixtures.DEFINITION.replace("steps:",
                "processLock: { name: card, key: \"${cardNumber}\" }\nsteps:"));
        assertThatThrownBy(lock::checkInvariants).hasMessageContaining("processLock.key");
    }

    @Test
    void anLlmPromptReadingASensitiveVariableNeedsTheOptIn() {
        var llmStep = """
                  - id: summarize
                    type: LLM_CALL
                    name: Summarize
                    preconditionStepId: start
                    llm:
                      connection: claude
                      prompt: "Summarise ${patient.history}"
                      outputVariable: summary
                """;
        var refused = SensitiveFixtures.definition(SensitiveFixtures.DEFINITION + llmStep);
        assertThatThrownBy(refused::checkInvariants).hasMessageContaining("llm.allowSensitiveVariables: true");
        var allowed = SensitiveFixtures.definition(SensitiveFixtures.DEFINITION
                + llmStep.replace("connection: claude", "connection: claude\n      allowSensitiveVariables: true"));
        allowed.checkInvariants();
        assertThat(allowed.steps().stream().filter(s -> "summarize".equals(s.id())).findFirst().orElseThrow()
                .llm().allowsSensitiveVariables()).isTrue();
    }

    @Test
    void theDeclarationIsPartOfTheVersion() {
        var versioning = new WorkflowDefinitionVersioningService(mock(WorkflowDefinitionVersionEntityRepository.class));
        var declared = SensitiveFixtures.definition();
        var undeclared = declared.withSensitiveVariables(null);
        var otherStepFlag = SensitiveFixtures.definition(SensitiveFixtures.DEFINITION.replace("sensitiveOutputs: true", ""));
        assertThat(versioning.contentHash(declared)).isNotEqualTo(versioning.contentHash(undeclared));
        assertThat(versioning.contentHash(declared)).isNotEqualTo(versioning.contentHash(otherStepFlag));
        // A definition that never declared any hashes as it did before the field existed.
        var plain = SensitiveFixtures.definition(SensitiveFixtures.DEFINITION
                .replace("sensitiveVariables: [cardNumber, \"patient.*\"]\n", "")
                .replace("    sensitiveOutputs: true\n", ""));
        assertThat(versioning.contentHash(plain)).isEqualTo(versioning.contentHash(plain.withSensitiveVariables(null)));
    }

    @Test
    void withoutAKeyADefinitionDeclaringSensitiveVariablesIsRefused() throws Exception {
        var validator = new WorkflowDefinitionValidator();
        validator.init();
        assertThatThrownBy(() -> validator.validate(SensitiveFixtures.definition()))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class)
                .hasMessageContaining("declares sensitive variables [cardNumber, patient.*, authCode]")
                .hasMessageContaining("workflow.encryption.keys.<id>");
        validator.encryption(SensitiveFixtures.keys());
        validator.validate(SensitiveFixtures.definition());
        // And the schema knows every new key.
        validator.validateSource(new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(SensitiveFixtures.DEFINITION), "payment.yaml");
        validator.encryption(null);
        assertThatThrownBy(() -> WorkflowDefinitionValidator.requireKeysFor(SensitiveFixtures.definition(), VariableEncryption.DISABLED))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class);
        WorkflowDefinitionValidator.requireKeysFor(SensitiveFixtures.definition().withSensitiveVariables(null)
                .withSteps(java.util.List.of()), VariableEncryption.DISABLED);
    }

    @Test
    void theStartupCheckFailsWhenTheStoreHoldsSuchADefinitionAndTheKeysAreGone() {
        var definitions = mock(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class);
        org.mockito.Mockito.when(definitions.findAll()).thenReturn(java.util.List.of(SensitiveFixtures.definition()));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("definitions", definitions);
        var withoutKeys = new SensitiveVariablesStartupCheck(beans.getBeanProvider(VariableEncryption.class),
                beans.getBeanProvider(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class));
        assertThatThrownBy(() -> withoutKeys.run(null)).hasMessageContaining("[payment] declare sensitive variables");
        beans.addBean("keys", SensitiveFixtures.keys());
        new SensitiveVariablesStartupCheck(beans.getBeanProvider(VariableEncryption.class),
                beans.getBeanProvider(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class)).check();
        // A database that is down at boot does not stop the engine (the writes still fail closed).
        var down = mock(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class);
        org.mockito.Mockito.when(down.findAll()).thenThrow(new IllegalStateException("connection refused"));
        var noKeys = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        noKeys.addBean("definitions", down);
        new SensitiveVariablesStartupCheck(noKeys.getBeanProvider(VariableEncryption.class),
                noKeys.getBeanProvider(io.mateu.workflow.application.out.WorkflowDefinitionRepository.class)).check();
    }
}
