package com.chanakya.shl2.model.dto.response;

import java.time.Instant;

public record MemberPreferencesResponse(
        boolean sharingEnabled,
        Instant updatedAt
) {}
