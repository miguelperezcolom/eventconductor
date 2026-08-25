package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Directories on the local filesystem to import workflow definitions from at startup.
 *
 * <p>The sibling of {@code workflow.git-import} for definitions that are already on disk — mounted
 * into a container, checked out beside the app, written by whatever generates them. Git import
 * reads what is <em>committed</em>, which is the right thing for a deployment and the wrong thing
 * for the loop where someone is writing a definition: edit, commit, restart, discover the commit
 * was the step you forgot.
 *
 * <pre>
 * workflow:
 *   directory-import:
 *     directories:
 *       - /definitions/workflows
 * </pre>
 */
@ConfigurationProperties(prefix = "workflow.directory-import")
@Getter
@Setter
public class DirectoryImportProperties {

    /** Directories to scan. Each is scanned recursively; a missing one is an error, not a silence. */
    private List<String> directories = new ArrayList<>();
}
