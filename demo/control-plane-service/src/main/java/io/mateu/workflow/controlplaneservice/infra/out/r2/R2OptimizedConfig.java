package io.mateu.workflow.controlplaneservice.infra.out.r2;

import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient; // <--- Este es el import
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import java.net.URI;

public class R2OptimizedConfig {
    public S3TransferManager createFastManager() {

        // Configuramos el cliente HTTP nativo (CRT)
        SdkAsyncHttpClient httpClient = AwsCrtAsyncHttpClient.builder()
                .maxConcurrency(100) // Número de conexiones simultáneas
                .build();

        // Se lo pasamos al cliente de S3
        S3AsyncClient s3Async = S3AsyncClient.builder()
                .endpointOverride(URI.create("https://<ACCOUNT_ID>.r2.cloudflarestorage.com"))
                .httpClient(httpClient)
                .region(software.amazon.awssdk.regions.Region.of("auto"))
                .build();

        return S3TransferManager.builder()
                .s3Client(s3Async)
                .build();
    }
}
