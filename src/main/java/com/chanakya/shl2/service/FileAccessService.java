package com.chanakya.shl2.service;

import com.chanakya.shl2.config.ShlProperties;
import com.chanakya.shl2.model.document.ShlFileDocument;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class FileAccessService {

    private final S3StorageService s3StorageService;
    private final Duration urlExpiry;

    public FileAccessService(S3StorageService s3StorageService, ShlProperties properties) {
        this.s3StorageService = s3StorageService;
        this.urlExpiry = Duration.ofSeconds(properties.fileUrlExpirySeconds());
    }

    public String generatePresignedUrl(ShlFileDocument file) {
        return s3StorageService.generatePresignedGetUrl(file.getS3Key(), urlExpiry);
    }
}
