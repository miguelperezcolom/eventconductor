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
        /** Optional username for HTTPS authentication. */
        private String username;
        /** Optional password / token for HTTPS authentication. */
        private String password;
    }
}
