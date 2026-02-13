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

@Service
public class ManifestService {

    private final ShlRepository shlRepository;
    private final ShlFileRepository fileRepository;
    private final PasscodeService passcodeService;
    private final FileAccessService fileAccessService;
    private final S3StorageService s3StorageService;
    private final MemberService memberService;
    private final AccessLogService accessLogService;

    public ManifestService(ShlRepository shlRepository,
                           ShlFileRepository fileRepository,
                           PasscodeService passcodeService,
                           FileAccessService fileAccessService,
                           S3StorageService s3StorageService,
                           MemberService memberService,
                           AccessLogService accessLogService) {
        this.shlRepository = shlRepository;
        this.fileRepository = fileRepository;
        this.passcodeService = passcodeService;
        this.fileAccessService = fileAccessService;
        this.s3StorageService = s3StorageService;
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
     * Returns the encrypted content downloaded from S3.
     */
    public Mono<String> handleDirectFileRequest(String manifestId, String recipient) {
        return shlRepository.findByManifestId(manifestId)
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMap(this::checkSharingEnabled)
                .flatMap(this::validateShlStatus)
                .flatMap(shl -> fileRepository.findByShlId(shl.getId()).next()
                        .flatMap(file -> accessLogService
                                .logAccess(shl, recipient, AccessType.DIRECT_FILE)
                                .then(s3StorageService.download(file.getS3Key()))));
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
                .flatMap(file -> toFileEntry(file, request.embeddedLengthMax()))
                .collectList()
                .map(files -> {
                    String status = shl.getFlags().contains(ShlFlag.L) ? "can-change" : "finalized";
                    return new ManifestResponse(status, files);
                });
    }

    private Mono<ManifestFileEntry> toFileEntry(ShlFileDocument file, Integer embeddedLengthMax) {
        String lastUpdated = file.getLastUpdated() != null
                ? file.getLastUpdated().toString() : null;

        // If embeddedLengthMax is set and content fits, download from S3 and embed
        if (embeddedLengthMax != null && embeddedLengthMax > 0
                && file.getContentLength() <= embeddedLengthMax) {
            return s3StorageService.download(file.getS3Key())
                    .map(content -> new ManifestFileEntry(
                            file.getContentType(),
                            null,
                            content,
                            lastUpdated
                    ));
        }

        // Otherwise, provide an S3 presigned URL
        String location = fileAccessService.generatePresignedUrl(file);
        return Mono.just(new ManifestFileEntry(
                file.getContentType(),
                location,
                null,
                lastUpdated
        ));
    }
}
