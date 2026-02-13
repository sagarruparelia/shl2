package com.chanakya.shl2.exception;

import com.chanakya.shl2.model.dto.response.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ShlNotFoundException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleNotFound(ShlNotFoundException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage())));
    }

    @ExceptionHandler(ShlExpiredException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleExpired(ShlExpiredException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("expired", ex.getMessage())));
    }

    @ExceptionHandler(ShlRevokedException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleRevoked(ShlRevokedException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("revoked", ex.getMessage())));
    }

    @ExceptionHandler(PasscodeRequiredException.class)
    public Mono<ResponseEntity<ErrorResponse>> handlePasscodeRequired(PasscodeRequiredException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("passcode_required", ex.getMessage())));
    }

    @ExceptionHandler(PasscodeInvalidException.class)
    public Mono<ResponseEntity<ErrorResponse>> handlePasscodeInvalid(PasscodeInvalidException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("passcode_invalid", ex.getMessage(), ex.getRemainingAttempts())));
    }

    @ExceptionHandler(PasscodeExhaustedException.class)
    public Mono<ResponseEntity<ErrorResponse>> handlePasscodeExhausted(PasscodeExhaustedException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("passcode_exhausted", ex.getMessage(), 0)));
    }

    @ExceptionHandler(HealthLakeException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleHealthLake(HealthLakeException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("healthlake_error", ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleIllegalState(IllegalStateException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("bad_request", ex.getMessage())));
    }
}
