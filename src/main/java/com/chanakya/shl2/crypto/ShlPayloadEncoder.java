package com.chanakya.shl2.crypto;

import com.chanakya.shl2.config.ShlProperties;
import com.chanakya.shl2.model.document.ShlDocument;
import com.chanakya.shl2.model.enums.ShlFlag;
import com.chanakya.shl2.util.Base64UrlUtil;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ShlPayloadEncoder {

    private static final Logger log = LoggerFactory.getLogger(ShlPayloadEncoder.class);

    private final ShlProperties properties;
    private final ObjectMapper objectMapper;

    public ShlPayloadEncoder(ShlProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds and encodes the SHL URI: shlink:/{base64url-encoded-payload}
     */
    public String encode(ShlDocument shl) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            // U-flag: url resolves directly via GET; otherwise manifest POST
            Set<ShlFlag> shlFlags = shl.getFlags();
            boolean hasUFlag = shlFlags != null && shlFlags.contains(ShlFlag.U);
            payload.put("url", hasUFlag
                    ? buildDirectUrl(shl.getManifestId())
                    : buildManifestUrl(shl.getManifestId()));
            payload.put("key", shl.getEncryptionKeyBase64());

            if (shl.getExpirationTime() != null) {
                payload.put("exp", shl.getExpirationTime().getEpochSecond());
            }

            Set<ShlFlag> flags = shl.getFlags();
            if (flags != null && !flags.isEmpty()) {
                String flagStr = flags.stream()
                        .map(ShlFlag::name)
                        .sorted()
                        .collect(Collectors.joining());
                payload.put("flag", flagStr);
            }

            if (shl.getLabel() != null && !shl.getLabel().isBlank()) {
                payload.put("label", shl.getLabel());
            }

            payload.put("v", 1);

            String json = objectMapper.writeValueAsString(payload);
            String encoded = Base64UrlUtil.encode(json.getBytes(StandardCharsets.UTF_8));
            String shlUri = "shlink:/" + encoded;
            if (shlUri.length() > 128) {
                log.warn("SHL URI length {} exceeds 128 characters (recommended max for QR reliability), shlId={}",
                        shlUri.length(), shl.getId());
            }
            return shlUri;
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode SHL payload", e);
        }
    }

    private String buildManifestUrl(String manifestId) {
        return properties.baseUrl() + "/api/shl/manifest/" + manifestId;
    }

    private String buildDirectUrl(String manifestId) {
        return properties.baseUrl() + "/api/shl/direct/" + manifestId;
    }
}
