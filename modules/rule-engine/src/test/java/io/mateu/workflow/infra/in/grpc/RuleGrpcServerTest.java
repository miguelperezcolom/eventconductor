package io.mateu.workflow.infra.in.grpc;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.mateu.workflow.application.out.RuleCatalogMetrics;
import io.mateu.workflow.application.out.RuleRepository;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import io.mateu.workflow.infra.out.grpc.RuleProtoMapper;
import io.mateu.workflow.rules.grpc.GetRuleRequest;
import io.mateu.workflow.rules.grpc.RuleDefinition;
import io.mateu.workflow.rules.grpc.RuleServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The catalogue's gRPC server, over a real socket: started, it serves rules; stopped, it is not running. */
class RuleGrpcServerTest {

    final RuleRepository rules = mock(RuleRepository.class);
    final RuleProtoMapper mapper = mock(RuleProtoMapper.class);
    RuleGrpcServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private RuleGrpcService service() {
        return new RuleGrpcService(rules, mapper, mock(RuleCatalogMetrics.class));
    }

    @Test
    void startedItServesRulesAndStoppedItIsDown() throws Exception {
        var rule = new Rule("discount", "Discount", null, RuleType.EXPRESSION, 1, 0, List.of(), "true",
                List.of(), null, null, null, null);
        when(rules.findById("discount")).thenReturn(Optional.of(rule));
        when(mapper.toProto(any())).thenReturn(RuleDefinition.newBuilder().setId("discount").build());
        var port = freePort();
        server = new RuleGrpcServer(port, service());
        assertThat(server.isRunning()).isFalse();

        server.start();
        assertThat(server.isRunning()).isTrue();

        var channel = Grpc.newChannelBuilder("localhost:" + port, InsecureChannelCredentials.create()).build();
        try {
            var served = RuleServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getRule(GetRuleRequest.newBuilder().setId("discount").build());
            assertThat(served.getId()).isEqualTo("discount");
        } finally {
            channel.shutdownNow();
        }

        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void aPortThatIsTakenFailsTheStartLoudly() throws Exception {
        try (var taken = new ServerSocket(0)) {
            server = new RuleGrpcServer(taken.getLocalPort(), service());
            assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(String.valueOf(taken.getLocalPort()));
            assertThat(server.isRunning()).isFalse();
        }
    }

    @Test
    void stoppingAServerThatNeverStartedIsHarmless() {
        server = new RuleGrpcServer(0, service());
        server.stop();
        assertThat(server.isRunning()).isFalse();
    }
}
