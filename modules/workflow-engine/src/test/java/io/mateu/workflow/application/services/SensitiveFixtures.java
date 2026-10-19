package io.mateu.workflow.application.services;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.crypto.AesGcmVariableCodec;
import io.mateu.workflow.crypto.VariableEncryption;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/** Shared fixtures for the sensitive-variable tests: a definition that declares some, keys, policies. */
public final class SensitiveFixtures {

    public static final String CARD = "4111111111111111";

    public static final String DEFINITION = """
            id: payment
            name: Payment
            sensitiveVariables: [cardNumber, "patient.*"]
            steps:
              - id: start
                type: START
                name: Start
              - id: charge
                type: ACTION
                name: Charge
                topic: work
                preconditionStepId: start
                outputVariables: [authCode]
                sensitiveOutputs: true
              - id: end
                type: END
                name: End
                preconditionStepId: charge
            """;

    private SensitiveFixtures() {
    }

    public static WorkflowDefinition definition() {
        return definition(DEFINITION);
    }

    public static WorkflowDefinition definition(String yaml) {
        try {
            return new YAMLMapper().readValue(yaml, WorkflowDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static VariableEncryption keys() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new VariableEncryption(new AesGcmVariableCodec("test-key", Map.of("test-key", key)));
    }

    public static Process process(String id) {
        var process = Process.create(id, definition(), "bk-" + id, List.of(
                new Variable("cardNumber", CARD), new Variable("amount", "120"),
                new Variable("patient", "{\"ssn\":\"123-45-6789\"}")));
        process.popEvents();
        return process;
    }

    public static SensitiveVariablePolicies policies(VariableEncryption encryption, ProcessRepository processes,
                                                     WorkflowDefinitionRepository definitions) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("encryption", encryption);
        if (processes != null) beans.addBean("processes", processes);
        if (definitions != null) beans.addBean("definitions", definitions);
        return new SensitiveVariablePolicies(beans.getBeanProvider(VariableEncryption.class),
                beans.getBeanProvider(ProcessRepository.class), beans.getBeanProvider(WorkflowDefinitionRepository.class));
    }
}
