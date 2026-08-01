package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.usecases.gitimport.ImportRulesFromGitUseCase;
import io.mateu.workflow.application.usecases.gitimport.ImportRulesFromGitUseCase.ImportRulesResult;
import io.mateu.workflow.infra.config.RuleGitImportProperties;
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
class RuleGitImportWebhookControllerTest {

    @Mock RuleGitImportProperties properties;
    @Mock ImportRulesFromGitUseCase importUseCase;

    RuleGitImportWebhookController controller;

    private final RuleGitImportProperties.GitRepository master = repo("https://github.com/org/rules.git", "master");

    private static RuleGitImportProperties.GitRepository repo(String url, String branch) {
        var r = new RuleGitImportProperties.GitRepository();
        r.setUrl(url);
        r.setBranch(branch);
        return r;
    }

    @BeforeEach
    void setUp() {
        controller = new RuleGitImportWebhookController(properties, importUseCase);
        lenient().when(properties.getWebhookSecret()).thenReturn(null);
        lenient().when(properties.getRepositories()).thenReturn(List.of(master));
        lenient().when(importUseCase.handle(anyList()))
                .thenReturn(new ImportRulesResult(List.of(), List.of(), List.of()));
    }

    private byte[] githubPush(String branch, String cloneUrl) {
        return ("{\"ref\":\"refs/heads/" + branch + "\",\"repository\":{\"clone_url\":\""
                + cloneUrl + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadsOnlyTheMatchingRepository() {
        controller.webhook("github", new HttpHeaders(), githubPush("master", "https://github.com/org/rules.git"));
        ArgumentCaptor<List<RuleGitImportProperties.GitRepository>> captor = ArgumentCaptor.forClass(List.class);
        verify(importUseCase, timeout(2000)).handle(captor.capture());
        assertThat(captor.getValue()).containsExactly(master);
    }

    @Test
    void ignoresPushesToOtherBranches() {
        var response = controller.webhook("github", new HttpHeaders(),
                githubPush("dev", "https://github.com/org/rules.git"));
        assertThat(response.getBody()).contains("ignored");
        verify(importUseCase, after(400).never()).handle(any());
    }

    @Test
    void rejectsInvalidSignature() {
        when(properties.getWebhookSecret()).thenReturn("s3cret");
        var headers = new HttpHeaders();
        headers.add("X-Hub-Signature-256", "sha256=deadbeef");
        assertThatThrownBy(() -> controller.webhook("github", headers,
                githubPush("master", "https://github.com/org/rules.git")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }
}
