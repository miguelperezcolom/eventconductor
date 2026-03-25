package com.example;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import net.devh.boot.grpc.examples.lib.HelloRequest;
import net.devh.boot.grpc.examples.lib.MyServiceGrpc;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args )
    {


        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 9092)
                .usePlaintext()
                .build();

        var service = MyServiceGrpc.newBlockingStub(channel);


        var response = service.sayHello(HelloRequest.newBuilder()
                        .setName("Mateu")
                .build());


        System.out.println( response.getMessage() );
    }
}
