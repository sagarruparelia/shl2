package com.chanakya.shl2.model.dto.response;

import java.time.Instant;

public record CreateShlResponse(
        String shlUri,
        String managementToken,
        String qrCodeDataUri,
        Instant expirationTime,
        String label
) {}
