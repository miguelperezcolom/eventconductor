package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "forms.git-import")
@Getter
@Setter
public class GitImportProperties {

    /** List of Git repositories to scan for form definition JSON files. */
    private List<GitRepository> repositories = new ArrayList<>();

    /**
     * Optional HMAC-SHA256 secret used to verify GitHub webhook payloads
     * (X-Hub-Signature-256 header). Leave blank to skip signature verification.
     */
    private String webhookSecret;

    @Getter
    @Setter
    public static class GitRepository {
        /** Git clone URL (https or ssh). */
        private String url;
        /** Branch to check out. Defaults to "main". */
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
        /** Optional username for HTTPS authentication. */
        private String username;
        /** Optional password / token for HTTPS authentication. */
        private String password;
    }
}
