package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Git repositories to import rule definitions from.
 *
 * <pre>
 *   rules:
 *     git-import:
 *       webhook-secret: mysecret   # optional, for the GitHub webhook
 *       repositories:
 *         - url: https://github.com/org/rule-defs.git
 *           branch: main
 * </pre>
 */
@ConfigurationProperties(prefix = "rules.git-import")
@Getter
@Setter
public class RuleGitImportProperties {

    private List<GitRepository> repositories = new ArrayList<>();

    private String webhookSecret;

    @Getter
    @Setter
    public static class GitRepository {
        private String url;
        private String branch = "main";

        /**
         * Subdirectory of the repository to scan, or null for the whole clone.
         *
         * <p>The workflow engine has had this since directory scoping was added; these two copies
         * did not, so the property was accepted by relaxed binding and then did nothing — a
         * repository that is not exclusively definitions was walked end to end, reporting a parse
         * error per unrelated YAML file and, worse, importing any that happened to look like one.
         */
        private String directory;
        private String username;
        private String password;
    }
}
