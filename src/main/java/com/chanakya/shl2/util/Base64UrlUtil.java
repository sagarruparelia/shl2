package com.chanakya.shl2.util;

import java.util.Base64;

public final class Base64UrlUtil {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Base64UrlUtil() {}

    public static String encode(byte[] data) {
        return ENCODER.encodeToString(data);
    }

    public static byte[] decode(String encoded) {
        return DECODER.decode(encoded);
    }
}
