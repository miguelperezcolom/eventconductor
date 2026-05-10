package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "workflow.git-import")
@Getter
@Setter
public class GitImportProperties {

    /** List of Git repositories to scan for workflow definition JSON files. */
    private List<GitRepository> repositories = new ArrayList<>();

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
