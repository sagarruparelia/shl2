package com.chanakya.shl2.service;

import com.chanakya.shl2.config.ShlProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class S3StorageService {

    private final S3AsyncClient s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;

    public S3StorageService(S3AsyncClient s3Client,
                            S3Presigner s3Presigner,
                            ShlProperties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucketName = properties.aws().s3BucketName();
    }

    public Mono<String> upload(String s3Key, String encryptedContent, Instant expirationDate) {
        byte[] contentBytes = encryptedContent.getBytes(StandardCharsets.UTF_8);

        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType("application/jose");

        if (expirationDate != null) {
            requestBuilder.tagging(Tagging.builder()
                    .tagSet(Tag.builder()
                            .key("expirationDate")
                            .value(expirationDate.toString())
                            .build())
                    .build());
        }

        return Mono.fromFuture(
                s3Client.putObject(requestBuilder.build(), AsyncRequestBody.fromBytes(contentBytes))
        ).thenReturn(s3Key);
    }

    public Mono<String> download(String s3Key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();

        return Mono.fromFuture(
                s3Client.getObject(request, AsyncResponseTransformer.toBytes())
        ).map(response -> response.asString(StandardCharsets.UTF_8));
    }

    public String generatePresignedGetUrl(String s3Key, Duration expiry) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .build())
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    public Mono<Void> deleteByPrefix(String prefix) {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();

        return Mono.fromFuture(s3Client.listObjectsV2(listRequest))
                .flatMap(listResponse -> {
                    List<ObjectIdentifier> objectIds = listResponse.contents().stream()
                            .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build())
                            .toList();

                    if (objectIds.isEmpty()) {
                        return Mono.empty();
                    }

                    DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                            .bucket(bucketName)
                            .delete(Delete.builder().objects(objectIds).build())
                            .build();

                    return Mono.fromFuture(s3Client.deleteObjects(deleteRequest));
                })
                .then();
    }
}
