package com.chanakya.shl2.model.dto.response;

import com.chanakya.shl2.model.enums.AccessType;

import java.time.Instant;

public record AccessLogEntry(
        String id,
        String shlId,
        String shlLabel,
        String recipient,
        AccessType accessType,
        Instant accessedAt
) {}
