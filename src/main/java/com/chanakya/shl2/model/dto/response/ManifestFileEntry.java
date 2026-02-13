package com.chanakya.shl2.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManifestFileEntry(
        String contentType,
        String location,
        String embedded,
        String lastUpdated
) {}
