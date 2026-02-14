package com.chanakya.shl2.service;

import com.chanakya.shl2.config.ShlProperties;
import com.chanakya.shl2.exception.PasscodeExhaustedException;
import com.chanakya.shl2.exception.PasscodeInvalidException;
import com.chanakya.shl2.exception.PasscodeRequiredException;
import com.chanakya.shl2.model.document.ShlDocument;
import com.chanakya.shl2.repository.ShlRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;

@Service
public class PasscodeService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final ShlRepository shlRepository;
    private final ShlProperties properties;

    public PasscodeService(ShlRepository shlRepository, ShlProperties properties) {
        this.shlRepository = shlRepository;
        this.properties = properties;
    }

    /**
     * Hashes a passcode using BCrypt.
     */
    public String hashPasscode(String passcode) {
        return encoder.encode(passcode);
    }

    /**
     * Verifies the passcode and atomically decrements remaining attempts on failure.
     * If lockout period has expired, resets attempts before checking.
     * Returns the SHL document on success, errors on failure.
     */
    public Mono<ShlDocument> verifyAndDecrement(ShlDocument shl, String providedPasscode) {
        if (shl.getPasscodeHash() == null) {
            return Mono.just(shl);
        }

        if (providedPasscode == null || providedPasscode.isBlank()) {
            return Mono.error(new PasscodeRequiredException("Passcode required"));
        }

        // Check if lockout has expired and reset attempts if so
        Mono<ShlDocument> preparedShl;
        if (shl.getPasscodeFailuresRemaining() != null && shl.getPasscodeFailuresRemaining() <= 0
                && shl.getPasscodeLockedUntil() != null && Instant.now().isAfter(shl.getPasscodeLockedUntil())) {
            shl.setPasscodeFailuresRemaining(properties.defaultPasscodeAttempts());
            shl.setPasscodeLockedUntil(null);
            preparedShl = shlRepository.save(shl);
        } else {
            preparedShl = Mono.just(shl);
        }

        return preparedShl.flatMap(currentShl -> {
            if (currentShl.getPasscodeFailuresRemaining() != null && currentShl.getPasscodeFailuresRemaining() <= 0) {
                return Mono.error(new PasscodeExhaustedException("Passcode attempts exhausted"));
            }

            // BCrypt verification on boundedElastic scheduler (blocking operation)
            return Mono.fromCallable(() -> encoder.matches(providedPasscode, currentShl.getPasscodeHash()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap((Boolean matches) -> {
                        if (matches) {
                            return Mono.<ShlDocument>just(currentShl);
                        }
                        // Atomically decrement attempts
                        return shlRepository.decrementPasscodeAttempts(currentShl.getManifestId())
                                .flatMap((ShlDocument updated) -> {
                                    int remaining = updated.getPasscodeFailuresRemaining() != null
                                            ? updated.getPasscodeFailuresRemaining() : 0;
                                    if (remaining <= 0) {
                                        // Set lockout time
                                        updated.setPasscodeLockedUntil(Instant.now().plus(
                                                Duration.ofMinutes(properties.passcodeLockoutDurationMinutes())));
                                        return shlRepository.save(updated)
                                                .then(Mono.<ShlDocument>error(new PasscodeExhaustedException("Passcode attempts exhausted")));
                                    }
                                    return Mono.<ShlDocument>error(new PasscodeInvalidException(remaining));
                                })
                                .switchIfEmpty(Mono.error(new PasscodeExhaustedException("Passcode attempts exhausted")));
                    });
        });
    }
}
