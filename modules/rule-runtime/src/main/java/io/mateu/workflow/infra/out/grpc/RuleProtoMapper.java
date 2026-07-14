package io.mateu.workflow.infra.out.grpc;

import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.rules.grpc.RuleDefinition;

/**
 * Maps between the proto envelope and the domain Rule. The proto carries the
 * canonical JSON of the rule, so deserialization is the same Jackson path used
 * by every other source.
 */
public class RuleProtoMapper {

    private final RuleJsonMapper ruleJsonMapper;

    public RuleProtoMapper(RuleJsonMapper ruleJsonMapper) {
        this.ruleJsonMapper = ruleJsonMapper;
    }

    public Rule toRule(RuleDefinition definition) {
        return ruleJsonMapper.toRule(definition.getRuleJson());
    }

    public RuleDefinition toProto(Rule rule) {
        return RuleDefinition.newBuilder()
                .setId(rule.id() != null ? rule.id() : "")
                .setName(rule.name() != null ? rule.name() : "")
                .setType(rule.type() != null ? rule.type().label() : "")
                .setVersion(rule.version())
                .setRuleJson(ruleJsonMapper.toJson(rule))
                .build();
    }
}
