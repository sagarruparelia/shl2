package com.chanakya.shl2.model.dto.response;

import com.chanakya.shl2.model.enums.ShlFlag;
import com.chanakya.shl2.model.enums.ShlStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record MemberShlSummary(
        String id,
        String label,
        ShlStatus status,
        Set<ShlFlag> flags,
        Instant expirationTime,
        List<String> categories,
        long fileCount,
        Instant createdAt,
        Instant updatedAt
) {}
