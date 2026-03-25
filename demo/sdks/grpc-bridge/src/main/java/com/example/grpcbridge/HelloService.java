package com.example.grpcbridge;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.examples.lib.HelloReply;
import net.devh.boot.grpc.examples.lib.HelloRequest;
import net.devh.boot.grpc.examples.lib.MyServiceGrpc;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class HelloService extends MyServiceGrpc.MyServiceImplBase {

    @Override
    public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 9091)
                .usePlaintext()
                .build();

        var service = MyServiceGrpc.newBlockingStub(channel);


        var response = service.sayHello(request);

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

}
