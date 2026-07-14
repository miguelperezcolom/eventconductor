package io.mateu.workflow.infra.in.grpc;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.ClassUtils;

import java.io.IOException;

/**
 * Plain grpc-java server hosting the catalog's RuleService, started with the
 * application lifecycle when rules.grpc.enabled=true. The gRPC reflection
 * service is registered when grpc-services is on the classpath (handy for
 * grpcurl).
 */
@Slf4j
public class RuleGrpcServer implements SmartLifecycle {

    private final int port;
    private final RuleGrpcService ruleGrpcService;
    private Server server;

    public RuleGrpcServer(int port, RuleGrpcService ruleGrpcService) {
        this.port = port;
        this.ruleGrpcService = ruleGrpcService;
    }

    @Override
    public void start() {
        var builder = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(ruleGrpcService);
        if (ClassUtils.isPresent("io.grpc.protobuf.services.ProtoReflectionServiceV1", null)) {
            builder.addService(ReflectionServiceFactory.create());
        }
        try {
            server = builder.build().start();
            log.info("Rule catalog gRPC server listening on port {}", port);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start the rule catalog gRPC server on port " + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            log.info("Rule catalog gRPC server stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    /** Isolated so io.grpc.protobuf.services classes only load when present. */
    private static class ReflectionServiceFactory {
        static io.grpc.BindableService create() {
            return io.grpc.protobuf.services.ProtoReflectionServiceV1.newInstance();
        }
    }
}
