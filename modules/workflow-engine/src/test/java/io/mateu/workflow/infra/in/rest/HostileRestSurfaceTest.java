package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.services.MessageDispatcher;
import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.infra.config.MessageApiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HARD-REST-01..12 — the two doors the engine opens to the outside world.
 *
 * <p>{@code POST /workflow/api/messages} lets any system that cannot produce to Kafka correlate a
 * message into a running process, and {@code POST /workflow/webhooks/{provider}} lets a git
 * provider make the engine re-import its definitions. Both take a body from someone who is not us.
 *
 * <p>Driven through MockMvc rather than by calling the controllers, because half of what is being
 * asserted <em>is</em> the layer in between: which body Spring refuses to bind at all, and what
 * status a refusal comes back as. A malformed body that answers 500 is not a working defence — it
 * is an unhandled exception with a stack trace in the log and, on a bad day, in the response.
 */
@ExtendWith(MockitoExtension.class)
class HostileRestSurfaceTest {

    @Mock MessageDispatcher messageDispatcher;
    @Mock GitImportProperties gitImportProperties;
    @Mock ImportWorkflowDefinitionsFromGitUseCase importUseCase;

    private static final String SQL = "'; DROP TABLE process_entity; --";
    private static final String XSS = "<script>alert(1)</script>";

    private MockMvc messages(String apiKey) {
        var properties = new MessageApiProperties();
        properties.setApiKey(apiKey);
        return MockMvcBuilders.standaloneSetup(
                new MessageRestController(properties, messageDispatcher)).build();
    }

    private MockMvc webhooks(String secret) {
        lenient().when(gitImportProperties.getWebhookSecret()).thenReturn(secret);
        lenient().when(gitImportProperties.getRepositories()).thenReturn(List.of());
        return MockMvcBuilders.standaloneSetup(
                new GitImportWebhookController(gitImportProperties, importUseCase)).build();
    }

    private static String body(String messageName, String correlationKey) {
        return "{ \"messageName\": " + quote(messageName)
                + ", \"correlationKey\": " + quote(correlationKey)
                + ", \"variables\": { \"k\": \"v\" } }";
    }

    private static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------- the message API

    /** HARD-REST-01. With a key configured, no key is no entry. */
    @Test
    void theMessageApiRefusesACallerWithNoKeyWhenAKeyIsConfigured() throws Exception {
        messages("s3cret").perform(post("/workflow/api/messages")
                        .contentType("application/json").content(body("m", "k")))
                .andExpect(status().isUnauthorized());

        verify(messageDispatcher, never()).dispatch(any());
    }

    /** HARD-REST-02. And a wrong key is no entry either — compared in constant time. */
    @Test
    void theMessageApiRefusesAWrongKey() throws Exception {
        messages("s3cret").perform(post("/workflow/api/messages")
                        .header("X-Api-Key", "s3crey")
                        .contentType("application/json").content(body("m", "k")))
                .andExpect(status().isUnauthorized());

        verify(messageDispatcher, never()).dispatch(any());
    }

    /** ...and the right one is. */
    @Test
    void theMessageApiAcceptsTheConfiguredKey() throws Exception {
        messages("s3cret").perform(post("/workflow/api/messages")
                        .header("X-Api-Key", "s3cret")
                        .contentType("application/json").content(body("m", "k")))
                .andExpect(status().isAccepted());
    }

    /** HARD-REST-03. A body missing what correlation needs is refused, and nothing is published. */
    @Test
    void aMessageWithNothingToCorrelateOnIsRefused() throws Exception {
        for (var incomplete : List.of(body(null, "k"), body("m", null), body("", "k"), body("m", "  "))) {
            messages(null).perform(post("/workflow/api/messages")
                            .contentType("application/json").content(incomplete))
                    .andExpect(status().isBadRequest());
        }
        verify(messageDispatcher, never()).dispatch(any());
    }

    /** HARD-REST-04. A body that is not JSON is a client error, not a server one. */
    @Test
    void aMalformedBodyIsFourHundredRatherThanFiveHundred() throws Exception {
        for (var malformed : List.of("{", "not json at all", "[]", "{\"messageName\":}")) {
            messages(null).perform(post("/workflow/api/messages")
                            .contentType("application/json").content(malformed))
                    .andExpect(status().is4xxClientError());
        }
        verify(messageDispatcher, never()).dispatch(any());
    }

    /**
     * HARD-REST-05. A JSON bomb: a few thousand nested arrays in a body of a few kilobytes. The
     * parser's own nesting limit is what stops it, and what is asserted is that the limit is
     * reached as a refusal rather than as a stack overflow inside the dispatcher thread.
     */
    @Test
    void aDeeplyNestedBodyIsRefusedRatherThanParsed() throws Exception {
        var bomb = "{\"messageName\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}";

        messages(null).perform(post("/workflow/api/messages")
                        .contentType("application/json").content(bomb))
                .andExpect(status().is4xxClientError());

        verify(messageDispatcher, never()).dispatch(any());
    }

    /**
     * HARD-REST-06. Injection-shaped names and keys are names and keys. They reach the dispatcher
     * exactly as sent - not escaped, not stripped - because what protects the database is the
     * parameterised query and what protects the browser is the escaping where it renders.
     */
    @Test
    void injectionShapedNamesAndKeysArePassedThroughVerbatim() throws Exception {
        messages(null).perform(post("/workflow/api/messages")
                        .contentType("application/json").content(body(SQL, XSS)))
                .andExpect(status().isAccepted());

        var captor = ArgumentCaptor.forClass(MessageReceived.class);
        verify(messageDispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().messageName()).isEqualTo(SQL);
        assertThat(captor.getValue().correlationKey()).isEqualTo(XSS);
    }

    /** HARD-REST-07. A megabyte of variable is carried, not choked on. */
    @Test
    void aVeryLargeVariableIsCarriedWhole() throws Exception {
        var oneMegabyte = "x".repeat(1_048_576);
        var payload = "{ \"messageName\": \"m\", \"correlationKey\": \"k\", \"variables\": { \"payload\": "
                + quote(oneMegabyte) + " } }";

        messages(null).perform(post("/workflow/api/messages")
                        .contentType("application/json").content(payload))
                .andExpect(status().isAccepted());

        var captor = ArgumentCaptor.forClass(MessageReceived.class);
        verify(messageDispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().variables().getFirst().value()).hasSize(oneMegabyte.length());
    }

    // ---------------------------------------------------------------- the git webhook

    /** HARD-REST-08. With a secret configured, an unsigned push imports nothing. */
    @Test
    void theWebhookRefusesAnUnsignedPushWhenASecretIsConfigured() throws Exception {
        webhooks("s3cret").perform(post("/workflow/webhooks/github")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        verify(importUseCase, never()).handle(any());
    }

    /** HARD-REST-09. A forged signature is refused, and so is one that is not even hex. */
    @Test
    void theWebhookRefusesAForgedSignature() throws Exception {
        for (var signature : List.of("sha256=" + "0".repeat(64), "sha256=nothex", "garbage", "")) {
            webhooks("s3cret").perform(post("/workflow/webhooks/github")
                            .header("X-Hub-Signature-256", signature)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        verify(importUseCase, never()).handle(any());
    }

    /**
     * HARD-REST-10. An unknown provider is read as {@code generic}, which is the strict reading:
     * it still needs a token. The alternative - an unrecognised path segment skipping verification
     * - would make the whole check optional to anyone who could type a URL.
     */
    @Test
    void anUnknownProviderStillHasToAuthenticate() throws Exception {
        for (var provider : List.of("nonsense", "github2", "generic.")) {
            webhooks("s3cret").perform(post("/workflow/webhooks/" + provider)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        verify(importUseCase, never()).handle(any());
    }

    /**
     * HARD-REST-11. With no secret configured, verification is skipped by design - but the payload
     * is still only a payload. A body that is not a push, not JSON, or not there at all is
     * acknowledged and cannot make the endpoint fail.
     */
    @Test
    void anUnreadablePayloadIsAcknowledgedRatherThanCrashing() throws Exception {
        for (var payload : List.of("", "not json", "[]", "{\"repository\": 42}", " ")) {
            webhooks(null).perform(post("/workflow/webhooks/generic")
                            .contentType("application/json").content(payload))
                    .andExpect(status().isAccepted());
        }
    }

    /** HARD-REST-12. A push naming a repository that is not ours imports nothing. */
    @Test
    void aPushForARepositoryNobodyConfiguredImportsNothing() throws Exception {
        webhooks(null).perform(post("/workflow/webhooks/github")
                        .contentType("application/json")
                        .content("{\"ref\":\"refs/heads/main\",\"repository\":"
                                + "{\"clone_url\":\"https://evil.example/theirs.git\"}}"))
                .andExpect(status().isAccepted());

        verify(importUseCase, never()).handle(any());
    }
}
