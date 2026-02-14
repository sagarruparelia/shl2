package com.chanakya.shl2.model.dto.request;

import com.chanakya.shl2.model.enums.FhirCategory;
import com.chanakya.shl2.model.enums.ShlFlag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record CreateShlRequest(
        @NotBlank String patientId,
        @NotEmpty List<FhirCategory> categories,
        Instant timeframeStart,
        Instant timeframeEnd,
        Instant expirationTime,
        @Size(max = 80) String label,
        @Size(min = 4, max = 20) String passcode,
        Set<ShlFlag> flags,
        boolean includeHealthCards,
        boolean generateQrCode
) {}
