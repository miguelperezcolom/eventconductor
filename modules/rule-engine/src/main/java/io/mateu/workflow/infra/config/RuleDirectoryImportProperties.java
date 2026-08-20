package io.mateu.workflow.infra.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "rules.directory-import")
@Getter
@Setter
public class RuleDirectoryImportProperties {

    private List<String> directories = new ArrayList<>();
}
