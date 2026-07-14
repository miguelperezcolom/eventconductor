package io.mateu.workflow.infra.out.classpath;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads rule definitions from classpath:/rules/*.{json,yaml,yml}. Active when
 * rules.source=classpath.
 */
public class ClasspathRuleSource implements RuleSource {

    private static final Logger log = LoggerFactory.getLogger(ClasspathRuleSource.class);

    private final RuleJsonMapper ruleJsonMapper;
    private final Map<String, Rule> rules = new ConcurrentHashMap<>();

    public ClasspathRuleSource(RuleJsonMapper ruleJsonMapper) {
        this.ruleJsonMapper = ruleJsonMapper;
        refresh();
    }

    @Override
    public final void refresh() {
        var resolver = new PathMatchingResourcePatternResolver();
        try {
            var resources = new ArrayList<Resource>();
            resources.addAll(Arrays.asList(resolver.getResources("classpath:/rules/*.json")));
            resources.addAll(Arrays.asList(resolver.getResources("classpath:/rules/*.{yaml,yml}")));
            for (var resource : resources) {
                try {
                    var content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    var rule = ruleJsonMapper.toRule(content);
                    rules.put(rule.id(), rule);
                    log.info("Loaded rule '{}' from classpath:{}", rule.id(), resource.getFilename());
                } catch (Exception e) {
                    log.error("Failed to load rule from classpath:{}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.warn("No rules found at classpath:/rules/ — directory may not exist");
        }
    }

    @Override
    public Optional<Rule> findById(String id) {
        return Optional.ofNullable(rules.get(id));
    }

    @Override
    public List<Rule> findAll() {
        return List.copyOf(rules.values());
    }
}
