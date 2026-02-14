package com.chanakya.shl2.controller;

import com.chanakya.shl2.crypto.JwsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
public class WellKnownController {

    private final JwsService jwsService;

    public WellKnownController(JwsService jwsService) {
        this.jwsService = jwsService;
    }

    /**
     * JWKS endpoint for SHC public key verification.
     */
    @GetMapping("/.well-known/jwks.json")
    public Mono<Map<String, Object>> getJwks() {
        return Mono.just(Map.of("keys", List.of(jwsService.getPublicJwk())));
    }
}
