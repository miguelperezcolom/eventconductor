package io.mateu.workflow.application.services;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.mateu.workflow.domain.Rule;

import java.util.List;

/**
 * Canonical (de)serialization of rule definitions. Plain Jackson (no UI
 * framework), JSON or YAML in, canonical JSON out — the same path is used by
 * classpath, REST, gRPC and Kafka sources so a rule always round-trips
 * identically.
 */
public class RuleJsonMapper {

    private final ObjectMapper jsonMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public Rule toRule(String content) {
        try {
            var trimmed = content.trim();
            if (trimmed.startsWith("{")) {
                return jsonMapper.readValue(trimmed, Rule.class);
            }
            return yamlMapper.readValue(content, Rule.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse rule definition", e);
        }
    }

    public List<Rule> toRuleList(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<List<Rule>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse rule list", e);
        }
    }

    public String toJson(Rule rule) {
        try {
            return jsonMapper.writeValueAsString(rule);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize rule " + rule.id(), e);
        }
    }
}
