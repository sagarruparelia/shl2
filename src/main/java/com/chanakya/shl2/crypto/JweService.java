package com.chanakya.shl2.crypto;

import com.chanakya.shl2.util.Base64UrlUtil;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Service
public class JweService {

    /**
     * Encrypts plaintext using AES-256-GCM with direct key agreement.
     * Nimbus auto-generates a unique IV per call.
     */
    public String encrypt(String plaintext, String keyBase64Url) {
        try {
            SecretKey key = toSecretKey(keyBase64Url);
            JWEHeader header = new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM);
            JWEObject jwe = new JWEObject(header, new Payload(plaintext.getBytes(StandardCharsets.UTF_8)));
            jwe.encrypt(new DirectEncrypter(key));
            return jwe.serialize();
        } catch (JOSEException e) {
            throw new RuntimeException("JWE encryption failed", e);
        }
    }

    /**
     * Decrypts a JWE compact serialization.
     */
    public String decrypt(String jweCompact, String keyBase64Url) {
        try {
            SecretKey key = toSecretKey(keyBase64Url);
            JWEObject jwe = JWEObject.parse(jweCompact);
            jwe.decrypt(new DirectDecrypter(key));
            return jwe.getPayload().toString();
        } catch (Exception e) {
            throw new RuntimeException("JWE decryption failed", e);
        }
    }

    private SecretKey toSecretKey(String keyBase64Url) {
        byte[] keyBytes = Base64UrlUtil.decode(keyBase64Url);
        return new SecretKeySpec(keyBytes, "AES");
    }
}
