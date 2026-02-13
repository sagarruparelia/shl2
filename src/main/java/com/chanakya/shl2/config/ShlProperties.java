package com.chanakya.shl2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shl")
public record ShlProperties(
        String baseUrl,
        int fileUrlExpirySeconds,
        int defaultPasscodeAttempts,
        Shc shc,
        Aws aws
) {
    public record Shc(
            String issuerUrl,
            String signingKeyPath
    ) {}

    public record Aws(
            String region,
            String healthlakeDatastoreId,
            String s3BucketName,
            String dynamoAccessLogTable
    ) {}
}
