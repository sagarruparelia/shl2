package com.chanakya.shl2.model.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ManifestRequest(
        @NotBlank String recipient,
        String passcode,
        Integer embeddedLengthMax
) {}
