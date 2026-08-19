package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.usecases.gitimport.ImportWorkflowDefinitionsFromGitUseCase;
import io.mateu.workflow.infra.config.GitImportProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The webhook through Spring MVC, rather than as a Java object.
 *
 * <p>{@link GitImportWebhookControllerTest} calls {@code controller.webhook(...)} directly, which
 * is the right shape for the routing and verification logic but cannot see the mapping layer at
 * all — and that is where this endpoint was broken for every released build. {@code provider} is
 * declared as a bare {@code @PathVariable String}, so Spring binds it by parameter name, and the
 * reactor compiled without {@code -parameters}: no {@code MethodParameters} attribute reached the
 * jars, and every real POST died with "Name for argument of type [java.lang.String] not specified"
 * before the signature was checked. A unit test on the object could never have caught it.
 *
 * <p>This one goes through the dispatcher, so it fails if the flag is ever lost again.
 */
@ExtendWith(MockitoExtension.class)
class GitImportWebhookControllerMvcTest {

    @Mock GitImportProperties properties;
    @Mock ImportWorkflowDefinitionsFromGitUseCase importUseCase;

    private MockMvc mockMvc(GitImportWebhookController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void binds_the_provider_path_variable() throws Exception {
        // No repositories: the endpoint answers 202 without cloning anything, which is all this
        // test needs — reaching the method body at all is the assertion.
        when(properties.getRepositories()).thenReturn(List.of());

        mockMvc(new GitImportWebhookController(properties, importUseCase))
                .perform(post("/workflow/webhooks/github")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void binds_it_for_every_provider_the_path_accepts() throws Exception {
        when(properties.getRepositories()).thenReturn(List.of());
        var mvc = mockMvc(new GitImportWebhookController(properties, importUseCase));

        for (var provider : List.of("github", "gitlab", "bitbucket", "generic")) {
            mvc.perform(post("/workflow/webhooks/" + provider)
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isAccepted());
        }
    }
}
