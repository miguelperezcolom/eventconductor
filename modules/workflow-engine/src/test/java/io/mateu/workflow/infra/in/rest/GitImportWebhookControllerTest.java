package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase.ImportWorkflowDefinitionsResult;
import io.mateu.workflow.infra.config.GitImportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitImportWebhookControllerTest {

    @Mock GitImportProperties properties;
    @Mock ImportWorkflowDefinitionsFromGitUseCase importUseCase;

    GitImportWebhookController controller;

    private static GitImportProperties.GitRepository repo(String url, String branch) {
        var r = new GitImportProperties.GitRepository();
        r.setUrl(url);
        r.setBranch(branch);
        return r;
    }

    private final GitImportProperties.GitRepository master =
            repo("https://github.com/org/defs.git", "master");

    @BeforeEach
    void setUp() {
        controller = new GitImportWebhookController(properties, importUseCase);
        lenient().when(properties.getWebhookSecret()).thenReturn(null);
        lenient().when(properties.getRepositories()).thenReturn(List.of(master));
        lenient().when(importUseCase.handle(anyList()))
                .thenReturn(new ImportWorkflowDefinitionsResult(List.of(), List.of(), List.of()));
    }

    private byte[] githubPush(String branch, String cloneUrl) {
        return ("{\"ref\":\"refs/heads/" + branch + "\",\"repository\":{\"clone_url\":\""
                + cloneUrl + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadsOnlyTheMatchingRepositoryOnAPushToTheConfiguredBranch() {
        var response = controller.webhook("github", new HttpHeaders(),
                githubPush("master", "https://github.com/org/defs.git"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        ArgumentCaptor<List<GitImportProperties.GitRepository>> captor = ArgumentCaptor.forClass(List.class);
        verify(importUseCase, timeout(2000)).handle(captor.capture());
        assertThat(captor.getValue()).containsExactly(master);
    }

    @Test
    void ignoresPushesToOtherBranches() {
        var response = controller.webhook("github", new HttpHeaders(),
                githubPush("feature-x", "https://github.com/org/defs.git"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).contains("ignored");
        verify(importUseCase, after(400).never()).handle(any());
    }

    @Test
    void ignoresPushesToOtherRepositories() {
        var response = controller.webhook("github", new HttpHeaders(),
                githubPush("master", "https://github.com/org/something-else.git"));

        assertThat(response.getBody()).contains("ignored");
        verify(importUseCase, after(400).never()).handle(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToReloadingEverythingWhenThePayloadCannotBeUnderstood() {
        controller.webhook("github", new HttpHeaders(), "not a json body".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<List<GitImportProperties.GitRepository>> captor = ArgumentCaptor.forClass(List.class);
        verify(importUseCase, timeout(2000)).handle(captor.capture());
        assertThat(captor.getValue()).containsExactly(master);
    }

    @Test
    void rejectsAnInvalidSignatureWhenASecretIsConfigured() {
        when(properties.getWebhookSecret()).thenReturn("s3cret");
        var headers = new HttpHeaders();
        headers.add("X-Hub-Signature-256", "sha256=deadbeef");

        assertThatThrownBy(() -> controller.webhook("github", headers,
                githubPush("master", "https://github.com/org/defs.git")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
        verify(importUseCase, after(300).never()).handle(any());
    }
}
