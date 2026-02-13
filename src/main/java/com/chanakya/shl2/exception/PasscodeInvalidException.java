package com.chanakya.shl2.exception;

import lombok.Getter;

@Getter
public class PasscodeInvalidException extends RuntimeException {

    private final int remainingAttempts;

    public PasscodeInvalidException(int remainingAttempts) {
        super("Invalid passcode");
        this.remainingAttempts = remainingAttempts;
    }
}
