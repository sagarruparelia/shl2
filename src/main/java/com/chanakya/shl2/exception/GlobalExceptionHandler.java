package com.chanakya.shl2.exception;

import com.chanakya.shl2.model.dto.response.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleValidation(WebExchangeBindException ex) {
        String message = ex.getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", message)));
    }

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

    /**
     * Per SHL spec: 401 body is exactly {"remainingAttempts": N}
     */
    @ExceptionHandler(PasscodeRequiredException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handlePasscodeRequired(PasscodeRequiredException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("remainingAttempts", -1)));
    }

    @ExceptionHandler(PasscodeInvalidException.class)
    public Mono<ResponseEntity<Map<String, Integer>>> handlePasscodeInvalid(PasscodeInvalidException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("remainingAttempts", ex.getRemainingAttempts())));
    }

    @ExceptionHandler(PasscodeExhaustedException.class)
    public Mono<ResponseEntity<Map<String, Integer>>> handlePasscodeExhausted(PasscodeExhaustedException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("remainingAttempts", 0)));
    }

    @ExceptionHandler(HealthLakeException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleHealthLake(HealthLakeException ex) {
        log.error("HealthLake error: {}", ex.getMessage(), ex);
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("healthlake_error", "Failed to retrieve health data")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleIllegalArgument(IllegalArgumentException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleIllegalState(IllegalStateException ex) {
        log.error("Illegal state: {}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("bad_request", "Invalid request state")));
    }
}
