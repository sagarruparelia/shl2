package com.chanakya.shl2.model.dto.request;

public record ManifestRequest(
        String recipient,
        String passcode,
        Integer embeddedLengthMax
) {}
