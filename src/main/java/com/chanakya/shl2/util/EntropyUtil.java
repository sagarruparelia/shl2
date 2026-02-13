package com.chanakya.shl2.util;

import java.security.SecureRandom;

public final class EntropyUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private EntropyUtil() {}

    /**
     * Generates a 256-bit random manifest ID as base64url (43 chars).
     */
    public static String generateManifestId() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64UrlUtil.encode(bytes);
    }

    /**
     * Generates a 256-bit random management token as base64url (43 chars).
     */
    public static String generateManagementToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64UrlUtil.encode(bytes);
    }
}
