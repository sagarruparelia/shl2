package com.chanakya.shl2.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String error,
        String message,
        Integer remainingAttempts
) {
    public ErrorResponse(String error, String message) {
        this(error, message, null);
    }
}
