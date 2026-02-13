package com.chanakya.shl2.model.dto.response;

import com.chanakya.shl2.model.enums.ShlFlag;
import com.chanakya.shl2.model.enums.ShlStatus;

import java.time.Instant;
import java.util.Set;

public record ShlStatusResponse(
        String manifestId,
        String label,
        ShlStatus status,
        Set<ShlFlag> flags,
        Instant expirationTime,
        long fileCount,
        Instant createdAt
) {}
