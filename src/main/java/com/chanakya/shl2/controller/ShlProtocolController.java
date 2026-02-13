package com.chanakya.shl2.controller;

import com.chanakya.shl2.model.dto.request.ManifestRequest;
import com.chanakya.shl2.model.dto.response.ManifestResponse;
import com.chanakya.shl2.service.FileAccessService;
import com.chanakya.shl2.service.ManifestService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/shl")
@CrossOrigin("*")
public class ShlProtocolController {

    private final ManifestService manifestService;
    private final FileAccessService fileAccessService;

    public ShlProtocolController(ManifestService manifestService, FileAccessService fileAccessService) {
        this.manifestService = manifestService;
        this.fileAccessService = fileAccessService;
    }

    /**
     * SHL manifest protocol endpoint.
     * POST /api/shl/manifest/{manifestId}
     */
    @PostMapping("/manifest/{manifestId}")
    public Mono<ManifestResponse> getManifest(
            @PathVariable String manifestId,
            @RequestBody ManifestRequest request) {
        return manifestService.processManifest(manifestId, request);
    }

    /**
     * File download via signed token.
     * GET /api/shl/file/{signedToken}
     */
    @GetMapping("/file/{signedToken}")
    public Mono<ResponseEntity<String>> getFile(@PathVariable String signedToken) {
        return fileAccessService.resolveSignedToken(signedToken)
                .map(file -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/jose"))
                        .body(file.getEncryptedContent()))
                .defaultIfEmpty(ResponseEntity.notFound().build());
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
        return manifestService.handleDirectFileRequest(manifestId)
                .map(file -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/jose"))
                        .body(file.getEncryptedContent()))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
