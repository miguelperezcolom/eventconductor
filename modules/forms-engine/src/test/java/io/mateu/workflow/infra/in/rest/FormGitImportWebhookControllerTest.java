package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.usecases.gitimport.ImportFormsFromGitUseCase;
import io.mateu.workflow.application.usecases.gitimport.ImportFormsFromGitUseCase.ImportFormsResult;
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
class FormGitImportWebhookControllerTest {

    @Mock GitImportProperties properties;
    @Mock ImportFormsFromGitUseCase importUseCase;

    FormGitImportWebhookController controller;

    private final GitImportProperties.GitRepository master = repo("https://github.com/org/forms.git", "master");

    private static GitImportProperties.GitRepository repo(String url, String branch) {
        var r = new GitImportProperties.GitRepository();
        r.setUrl(url);
        r.setBranch(branch);
        return r;
    }

    @BeforeEach
    void setUp() {
        controller = new FormGitImportWebhookController(properties, importUseCase);
        lenient().when(properties.getWebhookSecret()).thenReturn(null);
        lenient().when(properties.getRepositories()).thenReturn(List.of(master));
        lenient().when(importUseCase.handle(anyList()))
                .thenReturn(new ImportFormsResult(List.of(), List.of(), List.of()));
    }

    private byte[] githubPush(String branch, String cloneUrl) {
        return ("{\"ref\":\"refs/heads/" + branch + "\",\"repository\":{\"clone_url\":\""
                + cloneUrl + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadsOnlyTheMatchingRepository() {
        controller.webhook("github", new HttpHeaders(), githubPush("master", "https://github.com/org/forms.git"));
        ArgumentCaptor<List<GitImportProperties.GitRepository>> captor = ArgumentCaptor.forClass(List.class);
        verify(importUseCase, timeout(2000)).handle(captor.capture());
        assertThat(captor.getValue()).containsExactly(master);
    }

    @Test
    void ignoresPushesToOtherBranches() {
        var response = controller.webhook("github", new HttpHeaders(),
                githubPush("dev", "https://github.com/org/forms.git"));
        assertThat(response.getBody()).contains("ignored");
        verify(importUseCase, after(400).never()).handle(any());
    }

    @Test
    void rejectsInvalidSignature() {
        when(properties.getWebhookSecret()).thenReturn("s3cret");
        var headers = new HttpHeaders();
        headers.add("X-Hub-Signature-256", "sha256=deadbeef");
        assertThatThrownBy(() -> controller.webhook("github", headers,
                githubPush("master", "https://github.com/org/forms.git")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void withNoRepositoriesConfiguredThePushIsAcknowledgedAndNothingImported() {
        when(properties.getRepositories()).thenReturn(List.of());
        var response = controller.webhook("github", new HttpHeaders(),
                githubPush("master", "https://github.com/org/forms.git"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo("no repositories configured");
        verify(importUseCase, after(300).never()).handle(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPushWithoutAReadablePayloadReloadsEveryConfiguredRepository() {
        var other = repo("https://github.com/org/other.git", "main");
        when(properties.getRepositories()).thenReturn(List.of(master, other));

        var response = controller.webhook("github", new HttpHeaders(), null);

        assertThat(response.getBody()).isEqualTo("import scheduled for 2 repository/ies");
        ArgumentCaptor<List<GitImportProperties.GitRepository>> captor = ArgumentCaptor.forClass(List.class);
        verify(importUseCase, timeout(2000)).handle(captor.capture());
        assertThat(captor.getValue()).containsExactly(master, other);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aGenericPushNamingOnlyTheBranchReloadsTheRepositoriesOnIt() {
        var dev = repo("https://github.com/org/other.git", "dev");
        when(properties.getRepositories()).thenReturn(List.of(master, dev));

        controller.webhook("generic", new HttpHeaders(), "{\"branch\":\"dev\"}".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<List<GitImportProperties.GitRepository>> captor = ArgumentCaptor.forClass(List.class);
        verify(importUseCase, timeout(2000)).handle(captor.capture());
        assertThat(captor.getValue()).containsExactly(dev);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aGenericPushNamingOnlyTheRepositoryReloadsItOnAnyBranch() {
        var other = repo("https://github.com/org/other.git", "main");
        when(properties.getRepositories()).thenReturn(List.of(master, other));

        controller.webhook("generic", new HttpHeaders(),
                "{\"repository\":\"https://github.com/org/other.git\"}".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<List<GitImportProperties.GitRepository>> captor = ArgumentCaptor.forClass(List.class);
        verify(importUseCase, timeout(2000)).handle(captor.capture());
        assertThat(captor.getValue()).containsExactly(other);
    }

    @Test
    void aFailingImportIsLoggedAndTheWebhookStillAcknowledged() {
        when(importUseCase.handle(anyList())).thenThrow(new IllegalStateException("clone failed"));
        var response = controller.webhook("github", new HttpHeaders(),
                githubPush("master", "https://github.com/org/forms.git"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(importUseCase, timeout(2000)).handle(anyList());
    }

    @Test
    void anImportResultWithChangesIsAcknowledged() {
        when(importUseCase.handle(anyList())).thenReturn(new ImportFormsResult(List.of("A [a]"),
                List.of("bad.json: x"), List.of("Old [old]")));
        var response = controller.webhook("github", new HttpHeaders(),
                githubPush("master", "https://github.com/org/forms.git"));
        assertThat(response.getBody()).isEqualTo("import scheduled for 1 repository/ies");
        verify(importUseCase, timeout(2000)).handle(anyList());
    }
}
