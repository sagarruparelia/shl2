package com.chanakya.shl2.model.dto.response;

import java.util.List;

public record ManifestResponse(
        String status,
        List<ManifestFileEntry> files
) {}
