package io.mateu.workflow.infra.out.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.mateu.workflow.application.services.RuleJsonMapper;
import io.mateu.workflow.domain.Assignment;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import io.mateu.workflow.rules.grpc.GetRuleRequest;
import io.mateu.workflow.rules.grpc.ListRulesRequest;
import io.mateu.workflow.rules.grpc.ListRulesResponse;
import io.mateu.workflow.rules.grpc.RuleDefinition;
import io.mateu.workflow.rules.grpc.RuleServiceGrpc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcRuleSourceTest {

    static final RuleJsonMapper MAPPER = new RuleJsonMapper();
    static final RuleProtoMapper PROTO_MAPPER = new RuleProtoMapper(MAPPER);
    static final Rule RULE = new Rule("r1", "Rule one", null, RuleType.EXPRESSION, 1, 0, null,
            "true", List.of(new Assignment("out", "1")), null, null, null, null);

    static Server server;
    static ManagedChannel channel;
    static GrpcRuleSource source;

    @BeforeAll
    static void startServer() throws IOException {
        var name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new RuleServiceGrpc.RuleServiceImplBase() {
                    @Override
                    public void getRule(GetRuleRequest request, StreamObserver<RuleDefinition> observer) {
                        if (!"r1".equals(request.getId())) {
                            observer.onError(Status.NOT_FOUND.asRuntimeException());
                            return;
                        }
                        observer.onNext(PROTO_MAPPER.toProto(RULE));
                        observer.onCompleted();
                    }

                    @Override
                    public void listRules(ListRulesRequest request, StreamObserver<ListRulesResponse> observer) {
                        observer.onNext(ListRulesResponse.newBuilder()
                                .addRules(PROTO_MAPPER.toProto(RULE)).build());
                        observer.onCompleted();
                    }
                })
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        source = new GrpcRuleSource(channel, PROTO_MAPPER);
    }

    @AfterAll
    static void stopServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void fetchesRuleByIdOverGrpc() {
        assertThat(source.findById("r1")).hasValueSatisfying(rule -> {
            assertThat(rule.name()).isEqualTo("Rule one");
            assertThat(rule.type()).isEqualTo(RuleType.EXPRESSION);
        });
    }

    @Test
    void notFoundIsEmpty() {
        assertThat(source.findById("missing")).isEmpty();
    }

    @Test
    void listsAllRules() {
        assertThat(source.findAll()).hasSize(1);
    }

    @Test
    void protoEnvelopeCarriesMetadataAndCanonicalJson() {
        var proto = PROTO_MAPPER.toProto(RULE);

        assertThat(proto.getId()).isEqualTo("r1");
        assertThat(proto.getType()).isEqualTo("expression");
        assertThat(PROTO_MAPPER.toRule(proto)).isEqualTo(RULE);
    }
}
