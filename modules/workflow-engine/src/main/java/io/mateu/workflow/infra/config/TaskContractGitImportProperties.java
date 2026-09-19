package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Git repositories to import task contracts ({@code .ectask}) from, mirroring the workflow, form
 * and rule git imports.
 *
 * <pre>
 *   tasks:
 *     git-import:
 *       webhook-secret: mysecret   # optional
 *       repositories:
 *         - url: https://github.com/org/task-defs.git
 *           branch: main
 *           directory: definitions/tasks
 * </pre>
 */
@ConfigurationProperties(prefix = "tasks.git-import")
@Getter
@Setter
public class TaskContractGitImportProperties {

    private List<GitRepository> repositories = new ArrayList<>();

    private String webhookSecret;

    @Getter
    @Setter
    public static class GitRepository {
        private String url;
        private String branch = "main";
        /** Subdirectory of the repository to scan, or null for the whole clone. */
        private String directory;
        private String username;
        private String password;
    }
}
