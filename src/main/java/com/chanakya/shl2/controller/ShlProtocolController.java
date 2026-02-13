package com.chanakya.shl2.controller;

import com.chanakya.shl2.model.dto.request.ManifestRequest;
import com.chanakya.shl2.model.dto.response.ManifestResponse;
import com.chanakya.shl2.service.ManifestService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/shl")
@CrossOrigin("*")
public class ShlProtocolController {

    private final ManifestService manifestService;

    public ShlProtocolController(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    /**
     * SHL manifest protocol endpoint.
     * POST /api/shl/manifest/{manifestId}
     */
    @PostMapping("/manifest/{manifestId}")
    public Mono<ManifestResponse> getManifest(
            @PathVariable String manifestId,
            @Valid @RequestBody ManifestRequest request) {
        return manifestService.processManifest(manifestId, request);
    }

    /**
     * Direct file access for U-flag SHLs.
     * GET /api/shl/direct/{manifestId}?recipient=...
     * Per SHL spec, recipient query parameter is required.
     */
    @GetMapping("/direct/{manifestId}")
    public Mono<ResponseEntity<String>> getDirectFile(
            @PathVariable String manifestId,
            @RequestParam String recipient) {
        return manifestService.handleDirectFileRequest(manifestId, recipient)
                .map(content -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/jose"))
                        .body(content))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
