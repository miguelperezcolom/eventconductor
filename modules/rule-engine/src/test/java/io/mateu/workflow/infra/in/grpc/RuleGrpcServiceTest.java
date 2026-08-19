package io.mateu.workflow.infra.in.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import io.mateu.workflow.infra.out.grpc.RuleProtoMapper;
import io.mateu.workflow.rules.grpc.GetRuleRequest;
import io.mateu.workflow.rules.grpc.ListRulesRequest;
import io.mateu.workflow.rules.grpc.ListRulesResponse;
import io.mateu.workflow.rules.grpc.RuleDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gRPC half of the catalogue's read API — what a remote rule-runtime calls when it is not
 * using REST.
 *
 * <p>It is described in its own source as a "mirror of RuleReadController", and a mirror is exactly
 * the thing worth testing: two implementations of one contract drift, and the place they drift is
 * the edges. Both must answer *not found* rather than an empty rule, and both must treat an
 * unspecified tag as "everything" — which on the wire is not null but the empty string, since proto3
 * has no absent scalar.
 */
class RuleGrpcServiceTest {

    private RuleRepository rules;
    private RuleProtoMapper mapper;
    private RuleCatalogMetrics metrics;
    private RuleGrpcService service;

    @BeforeEach
    void setUp() {
        rules = mock(RuleRepository.class);
        mapper = mock(RuleProtoMapper.class);
        metrics = mock(RuleCatalogMetrics.class);
        service = new RuleGrpcService(rules, mapper, metrics);
        when(mapper.toProto(any())).thenReturn(RuleDefinition.getDefaultInstance());
    }

    @Test
    void a_rule_that_exists_is_served_and_counted() {
        var rule = rule("r1", List.of());
        when(rules.findById("r1")).thenReturn(Optional.of(rule));
        StreamObserver<RuleDefinition> observer = mock(StreamObserver.class);

        service.getRule(GetRuleRequest.newBuilder().setId("r1").build(), observer);

        verify(observer).onNext(any(RuleDefinition.class));
        verify(observer).onCompleted();
        verify(observer, never()).onError(any());
        // The transport is part of the measurement: the same rule served over REST and over gRPC
        // has to be distinguishable, or "which channel is my runtime actually using" is unanswerable.
        verify(metrics).ruleServed("r1", "grpc");
    }

    @Test
    void a_rule_that_does_not_exist_is_NOT_FOUND_rather_than_an_empty_definition() {
        when(rules.findById("missing")).thenReturn(Optional.empty());
        StreamObserver<RuleDefinition> observer = mock(StreamObserver.class);

        service.getRule(GetRuleRequest.newBuilder().setId("missing").build(), observer);

        var error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertThat(error.getValue()).isInstanceOf(StatusRuntimeException.class);
        var status = ((StatusRuntimeException) error.getValue()).getStatus();
        assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(status.getDescription()).contains("missing");

        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
        // Nothing was served, so nothing is counted as served.
        verify(metrics, never()).ruleServed(any(), any());
    }

    @Test
    void listing_without_a_tag_returns_the_whole_catalogue() {
        // proto3 has no absent scalar: "no tag" arrives as "". Treating that as a real tag would
        // answer an empty catalogue to every runtime that does not filter.
        when(rules.findAll()).thenReturn(List.of(rule("r1", List.of("pricing")), rule("r2", null)));
        StreamObserver<ListRulesResponse> observer = mock(StreamObserver.class);

        service.listRules(ListRulesRequest.getDefaultInstance(), observer);

        var response = ArgumentCaptor.forClass(ListRulesResponse.class);
        verify(observer).onNext(response.capture());
        verify(observer).onCompleted();
        assertThat(response.getValue().getRulesCount()).isEqualTo(2);
    }

    @Test
    void listing_with_a_tag_returns_only_the_rules_carrying_it() {
        when(rules.findAll()).thenReturn(List.of(
                rule("r1", List.of("pricing")), rule("r2", List.of("shipping")), rule("r3", null)));
        StreamObserver<ListRulesResponse> observer = mock(StreamObserver.class);

        service.listRules(ListRulesRequest.newBuilder().setTag("pricing").build(), observer);

        var response = ArgumentCaptor.forClass(ListRulesResponse.class);
        verify(observer).onNext(response.capture());
        assertThat(response.getValue().getRulesCount()).isEqualTo(1);
    }

    @Test
    void an_empty_catalogue_is_an_empty_response_and_not_an_error() {
        when(rules.findAll()).thenReturn(List.of());
        StreamObserver<ListRulesResponse> observer = mock(StreamObserver.class);

        service.listRules(ListRulesRequest.getDefaultInstance(), observer);

        var response = ArgumentCaptor.forClass(ListRulesResponse.class);
        verify(observer).onNext(response.capture());
        verify(observer).onCompleted();
        assertThat(response.getValue().getRulesCount()).isZero();
    }

    private static Rule rule(String id, List<String> tags) {
        return new Rule(id, "Rule " + id, "", RuleType.EXPRESSION, 1, 0, tags,
                "true", List.of(), null, null, null, null);
    }
}
