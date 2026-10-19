package io.mateu.workflow.application.updates;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.CommandDispatcher;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.crypto.AesGcmVariableCodec;
import io.mateu.workflow.crypto.VariableEncryption;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.ProcessUpdateRequested;
import io.mateu.workflow.infra.out.memory.InMemoryProcessUpdateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Updates of a definition with sensitive variables (#557): the payload fields a {@code set} writes into a
 * sensitive variable are sealed in the record and in the command, the request fingerprint is keyed, a result
 * that may read sensitive variables is sealed and opened only for its caller, and every other reader sees
 * masks.
 */
class ProcessUpdateSensitiveTest {

    static final String CARD = "4111111111111111";
    static final String DEFINITION = """
            {"id": "payments", "name": "Payments", "version": 1,
             "sensitiveVariables": ["cardNumber"],
             "steps": [
               {"id": "start", "type": "START", "name": "Start"},
               {"id": "end", "type": "END", "name": "End", "preconditionStepId": "start"}
             ],
             "updates": [
               {"name": "setCard", "accepts": {"type": "object", "required": ["card"]},
                "set": {"cardNumber": "payload.card", "last4": "payload.card.substring(12)"},
                "reply": "{'last4': last4}"},
               {"name": "showCard", "reply": "{'cardNumber': cardNumber, 'ok': true}", "allowSensitiveVariables": true}
             ]}""";

    final ProcessRepository processes = mock(ProcessRepository.class);
    final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    final LogMessageRepository logs = mock(LogMessageRepository.class);
    final ProcessLockService lock = mock(ProcessLockService.class);
    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final CreateProcessUseCase create = mock(CreateProcessUseCase.class);
    final CommandDispatcher dispatcher = mock(CommandDispatcher.class);
    final InMemoryProcessUpdateRepository updates = new InMemoryProcessUpdateRepository();
    final ProcessUpdateWaiters waiters = new ProcessUpdateWaiters(updates);
    final VariableEncryption encryption = encryption();

    WorkflowDefinition definition;
    ApplyProcessUpdateUseCase apply;
    ProcessUpdateService service;
    Process process;

    static VariableEncryption encryption() {
        var key = new byte[32];
        Arrays.fill(key, (byte) 3);
        return new VariableEncryption(new AesGcmVariableCodec("k1", Map.of("k1", key)));
    }

    @BeforeEach
    void setUp() throws Exception {
        definition = new ObjectMapper().findAndRegisterModules().readValue(DEFINITION, WorkflowDefinition.class);
        definition.checkInvariants();
        when(definitions.findById("payments")).thenReturn(Optional.of(definition));
        when(lock.runExclusively(anyString(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });
        waiters.start();
        apply = new ApplyProcessUpdateUseCase(updates, processes, steps, logs, lock, mock(MessageSubscriptionService.class),
                mock(StepOverProcessUseCase.class), mock(WorkflowMetrics.class), waiters);
        apply.encryption(encryption);
        service = new ProcessUpdateService(processes, updates, definitions, create, dispatcher, apply, waiters, logs,
                mock(WorkflowMetrics.class), new StaticListableBeanFactory().getBeanProvider(UpdateWithStartPolicy.class));
        service.encryption(encryption);
        process = Process.create("p", definition, "pay-1", new ArrayList<>(List.of(new Variable("cardNumber", "4000000000000002"))))
                .withStatus(ProcessStatus.RUNNING);
        when(processes.findById("p")).thenReturn(Optional.of(process));
        when(steps.findByProcessId("p")).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        waiters.stop();
    }

    ProcessUpdate inline(String name, String payload, String key) {
        var submitted = service.submitInline(new ProcessUpdateService.UpdateRequest("p", null, name, payload, key, null, null));
        return updates.findById(submitted.update().id()).orElseThrow();
    }

    @Test
    void theCardIsWrittenButNeverRecordedInClearAndTheFingerprintIsKeyed() {
        var update = inline("setCard", "{\"card\": \"" + CARD + "\"}", "k");

        assertThat(update.status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
        assertThat(update.result()).isEqualTo("{\"last4\":\"1111\"}");
        assertThat(process.getVariables()).contains(new Variable("cardNumber", CARD), new Variable("last4", "1111"));
        assertThat(update.payload()).doesNotContain(CARD).contains("ec:enc:v1:k1:");
        assertThat(update.requestHash()).startsWith("h1:k1:");
        assertThat(ProcessUpdateService.shown(update).payload()).isEqualTo("{\"card\":\"••••\"}");

        // A retry with the key is verified under the keyed fingerprint; another card is another request.
        assertThat(inline("setCard", "{ \"card\" : \"" + CARD + "\" }", "k").id()).isEqualTo(update.id());
        assertThatThrownBy(() -> inline("setCard", "{\"card\": \"5500000000000004\"}", "k"))
                .isInstanceOf(ProcessUpdateRejectedException.class).hasMessageContaining("already used");
    }

    @Test
    void inKafkaModeTheCommandCarriesTheCardSealedAndTheOwnerOpensIt() {
        service.mode = "kafka";
        service.submit(new ProcessUpdateService.UpdateRequest("p", null, "setCard", "{\"card\": \"" + CARD + "\"}",
                null, null, null));

        var command = ArgumentCaptor.forClass(ProcessUpdateRequested.class);
        verify(dispatcher).dispatch(command.capture());
        assertThat(command.getValue().payload()).doesNotContain(CARD).contains("ec:enc:v1:");

        apply.handle(command.getValue());

        assertThat(process.getVariables()).contains(new Variable("cardNumber", CARD));
        assertThat(updates.findById(command.getValue().updateId()).orElseThrow().status())
                .isEqualTo(ProcessUpdate.Status.COMPLETED);
    }

    @Test
    void aCommandStraightFromTheTopicIsRecordedSealedToo() {
        apply.handle(new ProcessUpdateRequested("t-1", "p", "setCard", "{\"card\": \"" + CARD + "\"}", "svc"));
        apply.handle(new ProcessUpdateRequested("t-2", "p", "nope", "{\"card\": \"" + CARD + "\"}", "svc"));

        var recorded = updates.findById("t-1").orElseThrow();
        assertThat(recorded.payload()).doesNotContain(CARD);
        assertThat(recorded.requestHash()).startsWith("h1:k1:");
        assertThat(updates.findById("t-2").orElseThrow().payload()).isEqualTo("\"••••\"");
    }

    @Test
    void aResultAllowedToReadTheCardIsSealedAndOpenedOnlyForItsCaller() {
        var update = inline("showCard", null, null);

        assertThat(update.result()).doesNotContain("4000000000000002").startsWith("\"ec:enc:v1:");
        assertThat(service.forCaller(update).result()).contains("\"cardNumber\":\"4000000000000002\"");
        assertThat(service.shownForProcess(update).result()).isEqualTo("\"••••\"");
        assertThat(ProcessUpdateService.shown(update).result()).isEqualTo("\"••••\"");
        assertThat(service.forCaller(null)).isNull();
        assertThat(service.shownForProcess(null)).isNull();
    }

    @Test
    void withoutKeysTheResultStaysInTheHeapButEveryReaderSeesItMasked() {
        service.encryption(null);
        apply.encryption(null);

        var update = inline("showCard", null, null);

        assertThat(service.forCaller(update).result()).contains("4000000000000002");
        assertThat(service.shownForProcess(update).result()).contains("\"cardNumber\":\"••••\"").doesNotContain("4000");
        assertThat(ProcessUpdateService.maskMembers("[1]", io.mateu.workflow.crypto.SensitiveNames.of(List.of("x"))))
                .isEqualTo("[1]");
        assertThat(ProcessUpdateService.maskMembers("{\"a\":1}", io.mateu.workflow.crypto.SensitiveNames.of(List.of("x"))))
                .isEqualTo("{\"a\":1}");
        assertThat(ProcessUpdateService.maskMembers("nope", io.mateu.workflow.crypto.SensitiveNames.of(List.of("x"))))
                .isEqualTo("nope");
    }

    @Test
    void anUpdateWithStartSealsTheCardInItsRecordAndSeedsTheVariable() {
        when(processes.findByBusinessKey("new")).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            CreateProcessCommand command = invocation.getArgument(0);
            assertThat(command.variables()).contains(new Variable("cardNumber", CARD));
            when(processes.findById(command.processId())).thenReturn(Optional.of(
                    Process.create(command.processId(), definition, "new", new ArrayList<>(command.variables()))));
            return null;
        }).when(create).handle(any());

        var started = service.updateWithStart(new ProcessUpdateService.StartRequest("payments", "new", Map.of(),
                "setCard", "{\"card\": \"" + CARD + "\"}", null, null, null));

        assertThat(started.started()).isTrue();
        assertThat(started.update().payload()).doesNotContain(CARD);
        assertThat(started.update().requestHash()).startsWith("h1:");
    }

    @Test
    void theStoredFingerprintOfALegacyDigestStillVerifies() {
        assertThat(ProcessUpdateService.fingerprint("setCard", "{}")).doesNotStartWith("h1:");
        assertThat(encryption.fingerprintMatches(ProcessUpdateService.fingerprint("setCard", "{}"),
                ProcessUpdateService.canonicalOf("setCard", "{}"))).isTrue();
    }
}
