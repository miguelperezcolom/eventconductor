package io.mateu.workflow.infra.out.grpc;

import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.rules.grpc.GetRuleRequest;
import io.mateu.workflow.rules.grpc.ListRulesRequest;
import io.mateu.workflow.rules.grpc.RuleServiceGrpc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Fetches rules from the catalog's gRPC read API. Active when
 * rules.source=grpc; the embedder must provide grpc-netty-shaded (the runtime
 * declares it optional). Usually wrapped in a CachingRuleSource.
 */
public class GrpcRuleSource implements RuleSource {

    private static final long DEADLINE_SECONDS = 30;

    private final RuleServiceGrpc.RuleServiceBlockingStub stub;
    private final RuleProtoMapper ruleProtoMapper;

    public GrpcRuleSource(String target, RuleProtoMapper ruleProtoMapper) {
        this(ManagedChannelBuilder.forTarget(target).usePlaintext().build(), ruleProtoMapper);
    }

    public GrpcRuleSource(Channel channel, RuleProtoMapper ruleProtoMapper) {
        this.stub = RuleServiceGrpc.newBlockingStub(channel);
        this.ruleProtoMapper = ruleProtoMapper;
    }

    @Override
    public Optional<Rule> findById(String id) {
        try {
            var definition = stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .getRule(GetRuleRequest.newBuilder().setId(id).build());
            return Optional.of(ruleProtoMapper.toRule(definition));
        } catch (StatusRuntimeException e) {
            if (Status.Code.NOT_FOUND.equals(e.getStatus().getCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<Rule> findAll() {
        var response = stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                .listRules(ListRulesRequest.newBuilder().build());
        return response.getRulesList().stream().map(ruleProtoMapper::toRule).toList();
    }
}
