package io.mateu.workflow.controlplaneservice.infra.out.r2;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedDirectoryUpload;
import software.amazon.awssdk.transfer.s3.model.DirectoryUpload;
import software.amazon.awssdk.transfer.s3.model.UploadDirectoryRequest;

import java.net.URI;
import java.nio.file.Paths;

import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient;

public class R2Client {
    static final String accountId = "763e3d775eccbcbc506637be3d39ccc5";
    // https://763e3d775eccbcbc506637be3d39ccc5.r2.cloudflarestorage.com
    // https://763e3d775eccbcbc506637be3d39ccc5.r2.cloudflarestorage.com
    // https://763e3d775eccbcbc506637be3d39ccc5.eu.r2.cloudflarestorage.com
    static final String accessKey = "13be0858258b58b6ea4667491b226b9f";
    static final String secretKey = "95696f08e6450f1f52889e9e8e1eae0a6ec610cd242c48a0561499edc8cfd2ae";

    public static S3Client getClient() {

        return S3Client.builder()
                .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of("auto")) // R2 usa "auto" o "us-east-1" por defecto
                .build();
    }

    public static S3AsyncClient getAsyncClient() {
        S3AsyncClient s3Async = S3AsyncClient.builder()
                .endpointOverride(URI.create("https://<ACCOUNT_ID>.r2.cloudflarestorage.com"))
                .region(Region.of("auto"))
                .httpClientBuilder(AwsCrtAsyncHttpClient.builder()
                        .maxConcurrency(100)) // 👈 Aumenta esto si tienes fibra óptica rápida y muchos archivos
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
        return s3Async;
    }


    public static void uploadToR2() {
        S3Client s3 = R2Client.getClient();
        String bucketName = "riu-assets";
        String key = "v21/es/index_ES.html"; // La ruta/nombre dentro del bucket
        String filePath = "./public/index_ES.html";

        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("text/html") // Importante para que el navegador lo renderice bien
                .build();

        s3.putObject(putOb, RequestBody.fromFile(Paths.get(filePath)));

        System.out.println("Archivo subido con éxito a R2: " + key);
    }


}
