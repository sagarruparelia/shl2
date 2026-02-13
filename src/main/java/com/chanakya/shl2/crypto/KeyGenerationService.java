package com.chanakya.shl2.crypto;

import com.chanakya.shl2.util.Base64UrlUtil;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class KeyGenerationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Generates a 256-bit AES key and returns it as base64url.
     */
    public String generateAes256Key() {
        byte[] key = new byte[32];
        SECURE_RANDOM.nextBytes(key);
        return Base64UrlUtil.encode(key);
    }

    /**
     * Generates an EC P-256 key pair for SHC signing.
     */
    public ECKey generateEcKeyPair() {
        try {
            return new ECKeyGenerator(Curve.P_256).generate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate EC key pair", e);
        }
    }
}
