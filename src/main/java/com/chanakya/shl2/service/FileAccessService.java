package com.chanakya.shl2.service;

import com.chanakya.shl2.config.ShlProperties;
import com.chanakya.shl2.model.document.ShlFileDocument;
import com.chanakya.shl2.repository.ShlFileRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class FileAccessService {

    private final ShlProperties properties;
    private final ShlFileRepository fileRepository;

    public FileAccessService(ShlProperties properties, ShlFileRepository fileRepository) {
        this.properties = properties;
        this.fileRepository = fileRepository;
    }

    /**
     * Generates a signed URL token for file access: {fileId}.{expiryEpoch}.{hmac}
     */
    public String generateSignedToken(String fileId) {
        long expiry = Instant.now().plusSeconds(properties.fileUrlExpirySeconds()).getEpochSecond();
        String data = fileId + "." + expiry;
        String hmac = computeHmac(data);
        return data + "." + hmac;
    }

    /**
     * Generates full signed URL for a file.
     */
    public String generateSignedUrl(String fileId) {
        return properties.baseUrl() + "/api/shl/file/" + generateSignedToken(fileId);
    }

    /**
     * Resolves a signed token back to the file document, verifying expiry and HMAC.
     */
    public Mono<ShlFileDocument> resolveSignedToken(String token) {
        String[] parts = token.split("\\.", 3);
        if (parts.length != 3) {
            return Mono.empty();
        }

        String fileId = parts[0];
        long expiry;
        try {
            expiry = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Mono.empty();
        }
        String providedHmac = parts[2];

        // Verify expiry
        if (Instant.now().getEpochSecond() > expiry) {
            return Mono.empty();
        }

        // Verify HMAC (constant-time comparison)
        String expectedHmac = computeHmac(fileId + "." + expiry);
        if (!MessageDigest.isEqual(
                expectedHmac.getBytes(StandardCharsets.UTF_8),
                providedHmac.getBytes(StandardCharsets.UTF_8))) {
            return Mono.empty();
        }

        return fileRepository.findById(fileId);
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    properties.signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
