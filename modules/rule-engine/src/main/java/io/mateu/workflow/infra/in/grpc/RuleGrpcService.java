package io.mateu.workflow.infra.in.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.infra.out.grpc.RuleProtoMapper;
import io.mateu.workflow.rules.grpc.GetRuleRequest;
import io.mateu.workflow.rules.grpc.ListRulesRequest;
import io.mateu.workflow.rules.grpc.ListRulesResponse;
import io.mateu.workflow.rules.grpc.RuleDefinition;
import io.mateu.workflow.rules.grpc.RuleServiceGrpc;

/**
 * gRPC read API of the rule catalog, mirror of RuleReadController.
 */
public class RuleGrpcService extends RuleServiceGrpc.RuleServiceImplBase {

    private final RuleRepository ruleRepository;
    private final RuleProtoMapper ruleProtoMapper;
    private final RuleCatalogMetrics ruleCatalogMetrics;

    public RuleGrpcService(RuleRepository ruleRepository, RuleProtoMapper ruleProtoMapper,
                           RuleCatalogMetrics ruleCatalogMetrics) {
        this.ruleRepository = ruleRepository;
        this.ruleProtoMapper = ruleProtoMapper;
        this.ruleCatalogMetrics = ruleCatalogMetrics;
    }

    @Override
    public void getRule(GetRuleRequest request, StreamObserver<RuleDefinition> responseObserver) {
        ruleRepository.findById(request.getId()).ifPresentOrElse(
                rule -> {
                    responseObserver.onNext(ruleProtoMapper.toProto(rule));
                    responseObserver.onCompleted();
                    ruleCatalogMetrics.ruleServed(request.getId(), "grpc");
                },
                () -> responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Rule not found: " + request.getId())
                        .asRuntimeException()));
    }

    @Override
    public void listRules(ListRulesRequest request, StreamObserver<ListRulesResponse> responseObserver) {
        var builder = ListRulesResponse.newBuilder();
        ruleRepository.findAll().stream()
                .filter(rule -> request.getTag().isEmpty() || rule.hasTag(request.getTag()))
                .map(ruleProtoMapper::toProto)
                .forEach(builder::addRules);
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
