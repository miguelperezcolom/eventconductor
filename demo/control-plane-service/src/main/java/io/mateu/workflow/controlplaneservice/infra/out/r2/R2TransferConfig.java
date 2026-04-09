package io.mateu.workflow.controlplaneservice.infra.out.r2;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import java.net.URI;

public class R2TransferConfig {
    public static S3TransferManager createTransferManager() {
        S3AsyncClient s3Async = S3AsyncClient.builder()
                .endpointOverride(URI.create("https://<ACCOUNT_ID>.r2.cloudflarestorage.com"))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("<ACCESS_KEY>", "<SECRET_KEY>")))
                .build();

        return S3TransferManager.builder()
                .s3Client(s3Async)
                .build();
    }
}