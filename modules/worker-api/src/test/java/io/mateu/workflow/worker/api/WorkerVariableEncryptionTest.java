package io.mateu.workflow.worker.api;

import io.mateu.workflow.crypto.AesGcmVariableCodec;
import io.mateu.workflow.crypto.VariableEncryption;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerVariableEncryptionTest {

    record ChargeInput(String cardNumber, int amount) {}

    record ChargeOutput(String cardNumber, String authCode, String receipt) {}

    static VariableEncryption keys() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new VariableEncryption(new AesGcmVariableCodec("k1", Map.of("k1", key)));
    }

    @Test
    void aWorkerWithTheKeysBindsPlaintextAndSealsItsAnswerAgain() {
        var keys = keys();
        var seen = new String[1];
        TaskHandler<ChargeInput, ChargeOutput> handler = (in, ctx) -> {
            seen[0] = in.cardNumber();
            return new ChargeOutput(in.cardNumber(), "A-1", "ok");
        };
        var registry = new TaskRegistry(List.of(new TaskRegistration<>("charge", 1, "charge.v1",
                ChargeInput.class, ChargeOutput.class, handler)));
        var sink = new TaskDispatcherTest.RecordingSink();
        var properties = new WorkerProperties();
        properties.getEncryption().setSensitiveVariables(List.of("auth*"));
        var dispatcher = new TaskDispatcher(registry, sink, Cancellations.NONE, false, TaskTracing.NOOP,
                properties.workerEncryption(keys));

        dispatcher.dispatch(new TaskExecutionRequested("tx", "p", "wf", "charge", "charge@1",
                List.of(new Variable("cardNumber", keys.seal("4111111111111111")), new Variable("amount", "10"))));

        assertThat(seen[0]).isEqualTo("4111111111111111");
        var answer = sink.completed.getFirst();
        var byName = answer.stream().collect(java.util.stream.Collectors.toMap(Variable::name, Variable::value));
        // Arrived sealed, goes back sealed; configured sensitive, sealed; the rest as it is.
        assertThat(byName.get("cardNumber")).startsWith("ec:enc:v1:k1:");
        assertThat(keys.open(byName.get("cardNumber"))).isEqualTo("4111111111111111");
        assertThat(byName.get("authCode")).startsWith("ec:enc:v1:");
        assertThat(byName.get("receipt")).isEqualTo("ok");
    }

    @Test
    void aWorkerWithoutKeysSeesTheSealedStrings() {
        var keys = keys();
        var sealed = keys.seal("4111");
        var opened = WorkerVariableEncryption.NONE.open(List.of(new Variable("cardNumber", sealed)));
        assertThat(opened.variables().getFirst().value()).isEqualTo(sealed);
        assertThat(opened.sealedNames()).isEmpty();
        assertThat(WorkerVariableEncryption.NONE.seal(List.of(new Variable("x", "y")), Set.of("x")))
                .containsExactly(new Variable("x", "y"));
        var off = new WorkerProperties();
        off.getEncryption().setEnabled(false);
        assertThat(off.workerEncryption(keys).enabled()).isFalse();
        assertThat(new WorkerProperties().workerEncryption(VariableEncryption.DISABLED)).isSameAs(WorkerVariableEncryption.NONE);
        off.getEncryption().setSensitiveVariables(null);
        assertThat(off.getEncryption().getSensitiveVariables()).isEmpty();
        assertThat(off.getEncryption().isEnabled()).isFalse();
    }

    @Test
    void aValueSealedWithAnUnknownKeyFailsTheTaskWithoutQuotingIt() {
        var other = keys();
        var registry = new TaskRegistry(List.of(new TaskRegistration<>("charge", 1, "charge.v1",
                ChargeInput.class, ChargeOutput.class, (in, ctx) -> new ChargeOutput(null, null, null))));
        var sink = new TaskDispatcherTest.RecordingSink();
        var dispatcher = new TaskDispatcher(registry, sink, Cancellations.NONE, false, TaskTracing.NOOP,
                new WorkerProperties().workerEncryption(keys()));
        var foreign = other.seal("4111");
        dispatcher.dispatch(new TaskExecutionRequested("tx", "p", "wf", "charge", "charge@1",
                List.of(new Variable("cardNumber", foreign))));
        assertThat(sink.failed).singleElement().asString().contains("failed authentication").doesNotContain("4111");
    }
}
