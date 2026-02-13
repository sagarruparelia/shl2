package com.chanakya.shl2.model.dto.request;

import jakarta.validation.constraints.NotNull;

public record UpdateMemberPreferencesRequest(
        @NotNull Boolean sharingEnabled
) {}
