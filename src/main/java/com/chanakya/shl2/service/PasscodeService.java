package com.chanakya.shl2.service;

import com.chanakya.shl2.exception.PasscodeExhaustedException;
import com.chanakya.shl2.exception.PasscodeInvalidException;
import com.chanakya.shl2.exception.PasscodeRequiredException;
import com.chanakya.shl2.model.document.ShlDocument;
import com.chanakya.shl2.repository.ShlRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class PasscodeService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final ShlRepository shlRepository;

    public PasscodeService(ShlRepository shlRepository) {
        this.shlRepository = shlRepository;
    }

    /**
     * Hashes a passcode using BCrypt.
     */
    public String hashPasscode(String passcode) {
        return encoder.encode(passcode);
    }

    /**
     * Verifies the passcode and atomically decrements remaining attempts on failure.
     * Returns the SHL document on success, errors on failure.
     */
    public Mono<ShlDocument> verifyAndDecrement(ShlDocument shl, String providedPasscode) {
        if (shl.getPasscodeHash() == null) {
            return Mono.just(shl);
        }

        if (providedPasscode == null || providedPasscode.isBlank()) {
            return Mono.error(new PasscodeRequiredException("Passcode required"));
        }

        if (shl.getPasscodeFailuresRemaining() != null && shl.getPasscodeFailuresRemaining() <= 0) {
            return Mono.error(new PasscodeExhaustedException("Passcode attempts exhausted"));
        }

        // BCrypt verification on boundedElastic scheduler (blocking operation)
        return Mono.fromCallable(() -> encoder.matches(providedPasscode, shl.getPasscodeHash()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap((Boolean matches) -> {
                    if (matches) {
                        return Mono.<ShlDocument>just(shl);
                    }
                    // Atomically decrement attempts
                    return shlRepository.decrementPasscodeAttempts(shl.getManifestId())
                            .flatMap((ShlDocument updated) -> {
                                int remaining = updated.getPasscodeFailuresRemaining() != null
                                        ? updated.getPasscodeFailuresRemaining() : 0;
                                if (remaining <= 0) {
                                    return Mono.<ShlDocument>error(new PasscodeExhaustedException("Passcode attempts exhausted"));
                                }
                                return Mono.<ShlDocument>error(new PasscodeInvalidException(remaining));
                            })
                            .switchIfEmpty(Mono.error(new PasscodeExhaustedException("Passcode attempts exhausted")));
                });
    }
}
