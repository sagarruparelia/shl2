package com.chanakya.shl2.controller;

import com.chanakya.shl2.model.dto.request.CreateShlRequest;
import com.chanakya.shl2.model.dto.response.CreateShlResponse;
import com.chanakya.shl2.model.dto.response.ShlStatusResponse;
import com.chanakya.shl2.service.ShlCreationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

// TODO: Add authentication (OAuth2/JWT) — management endpoints must be protected
@RestController
@RequestMapping("/api/shl")
public class ShlManagementController {

    private final ShlCreationService shlCreationService;

    public ShlManagementController(ShlCreationService shlCreationService) {
        this.shlCreationService = shlCreationService;
    }

    /**
     * Create a new SHL.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CreateShlResponse> createShl(@Valid @RequestBody CreateShlRequest request) {
        return shlCreationService.createShl(request);
    }

    /**
     * Get SHL status by management token.
     */
    @GetMapping("/manage/{managementToken}")
    public Mono<ShlStatusResponse> getStatus(@PathVariable String managementToken) {
        return shlCreationService.getStatus(managementToken);
    }

    /**
     * Revoke an SHL by management token.
     */
    @DeleteMapping("/manage/{managementToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revokeShl(@PathVariable String managementToken) {
        return shlCreationService.revokeShl(managementToken);
    }

    /**
     * Refresh SHL data (L-flag only).
     */
    @PostMapping("/manage/{managementToken}/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> refreshShl(@PathVariable String managementToken) {
        return shlCreationService.refreshShlData(managementToken);
    }

    /**
     * Generate QR code PNG for an SHL.
     */
    @GetMapping(value = "/manage/{managementToken}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public Mono<byte[]> getQrCode(@PathVariable String managementToken) {
        return shlCreationService.generateQrCode(managementToken);
    }
}
