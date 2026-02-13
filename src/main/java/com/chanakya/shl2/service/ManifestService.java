package com.chanakya.shl2.service;

import com.chanakya.shl2.exception.ShlExpiredException;
import com.chanakya.shl2.exception.ShlNotFoundException;
import com.chanakya.shl2.exception.ShlRevokedException;
import com.chanakya.shl2.model.document.ShlDocument;
import com.chanakya.shl2.model.document.ShlFileDocument;
import com.chanakya.shl2.model.dto.request.ManifestRequest;
import com.chanakya.shl2.model.dto.response.ManifestFileEntry;
import com.chanakya.shl2.model.dto.response.ManifestResponse;
import com.chanakya.shl2.model.enums.AccessType;
import com.chanakya.shl2.model.enums.ShlFlag;
import com.chanakya.shl2.model.enums.ShlStatus;
import com.chanakya.shl2.repository.ShlFileRepository;
import com.chanakya.shl2.repository.ShlRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Service
public class ManifestService {

    private final ShlRepository shlRepository;
    private final ShlFileRepository fileRepository;
    private final PasscodeService passcodeService;
    private final FileAccessService fileAccessService;
    private final MemberService memberService;
    private final AccessLogService accessLogService;

    public ManifestService(ShlRepository shlRepository,
                           ShlFileRepository fileRepository,
                           PasscodeService passcodeService,
                           FileAccessService fileAccessService,
                           MemberService memberService,
                           AccessLogService accessLogService) {
        this.shlRepository = shlRepository;
        this.fileRepository = fileRepository;
        this.passcodeService = passcodeService;
        this.fileAccessService = fileAccessService;
        this.memberService = memberService;
        this.accessLogService = accessLogService;
    }

    /**
     * Handles the SHL manifest protocol request (POST /api/shl/manifest/{manifestId}).
     */
    public Mono<ManifestResponse> processManifest(String manifestId, ManifestRequest request) {
        return shlRepository.findByManifestId(manifestId)
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMap(this::checkSharingEnabled)
                .flatMap(this::validateShlStatus)
                .flatMap(shl -> passcodeService.verifyAndDecrement(shl, request.passcode()))
                .flatMap(shl -> buildManifestResponse(shl, request)
                        .flatMap(response -> accessLogService
                                .logAccess(shl, request.recipient(), AccessType.MANIFEST)
                                .thenReturn(response)));
    }

    /**
     * Handles direct file request for U-flag SHLs (GET /api/shl/direct/{manifestId}).
     */
    public Mono<ShlFileDocument> handleDirectFileRequest(String manifestId, String recipient) {
        return shlRepository.findByManifestId(manifestId)
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMap(this::checkSharingEnabled)
                .flatMap(this::validateShlStatus)
                .flatMap(shl -> fileRepository.findByShlId(shl.getId()).next()
                        .flatMap(file -> accessLogService
                                .logAccess(shl, recipient, AccessType.DIRECT_FILE)
                                .thenReturn(file)));
    }

    private Mono<ShlDocument> checkSharingEnabled(ShlDocument shl) {
        return memberService.isSharingEnabled(shl.getPatientId())
                .flatMap(enabled -> enabled
                        ? Mono.just(shl)
                        : Mono.error(new ShlRevokedException("SHL is not available")));
    }

    private Mono<ShlDocument> validateShlStatus(ShlDocument shl) {
        if (shl.getStatus() == ShlStatus.REVOKED) {
            return Mono.error(new ShlRevokedException("SHL has been revoked"));
        }

        if (shl.getExpirationTime() != null && Instant.now().isAfter(shl.getExpirationTime())) {
            return Mono.error(new ShlExpiredException("SHL has expired"));
        }

        return Mono.just(shl);
    }

    private Mono<ManifestResponse> buildManifestResponse(ShlDocument shl, ManifestRequest request) {
        return fileRepository.findByShlId(shl.getId())
                .map(file -> toFileEntry(file, request.embeddedLengthMax()))
                .collectList()
                .map(files -> {
                    // Per SHL spec: "can-change" for L-flag, "finalized" otherwise
                    String status = shl.getFlags().contains(ShlFlag.L) ? "can-change" : "finalized";
                    return new ManifestResponse(status, files);
                });
    }

    private ManifestFileEntry toFileEntry(ShlFileDocument file, Integer embeddedLengthMax) {
        String lastUpdated = file.getLastUpdated() != null
                ? file.getLastUpdated().toString() : null;

        // If embeddedLengthMax is set and content fits, embed it
        if (embeddedLengthMax != null && embeddedLengthMax > 0
                && file.getEncryptedContent().length() <= embeddedLengthMax) {
            return new ManifestFileEntry(
                    file.getContentType(),
                    null,
                    file.getEncryptedContent(),
                    lastUpdated
            );
        }

        // Otherwise, provide a signed location URL
        String location = fileAccessService.generateSignedUrl(file.getId());
        return new ManifestFileEntry(
                file.getContentType(),
                location,
                null,
                lastUpdated
        );
    }
}
