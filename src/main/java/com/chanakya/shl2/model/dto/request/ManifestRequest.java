package com.chanakya.shl2.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ManifestRequest(
        @NotBlank @Size(max = 200) String recipient,
        @Size(max = 100) String passcode,
        @Positive Integer embeddedLengthMax
) {}
