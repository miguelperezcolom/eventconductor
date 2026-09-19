package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Local directories to import task contracts ({@code .ectask}) from, mirroring the workflow, form
 * and rule directory imports.
 *
 * <pre>
 *   tasks:
 *     directory-import:
 *       directories:
 *         - /etc/eventconductor/tasks
 * </pre>
 */
@ConfigurationProperties(prefix = "tasks.directory-import")
@Getter
@Setter
public class TaskContractDirectoryImportProperties {

    private List<String> directories = new ArrayList<>();
}
