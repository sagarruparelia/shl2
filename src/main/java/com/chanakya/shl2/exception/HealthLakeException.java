package com.chanakya.shl2.exception;

public class HealthLakeException extends RuntimeException {
    public HealthLakeException(String message) {
        super(message);
    }

    public HealthLakeException(String message, Throwable cause) {
        super(message, cause);
    }
}
