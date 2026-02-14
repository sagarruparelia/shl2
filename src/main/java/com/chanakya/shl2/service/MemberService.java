package com.chanakya.shl2.service;

import com.chanakya.shl2.exception.ShlNotFoundException;
import com.chanakya.shl2.model.document.MemberPreferencesDocument;
import com.chanakya.shl2.model.dto.request.UpdateMemberPreferencesRequest;
import com.chanakya.shl2.model.dto.response.MemberPreferencesResponse;
import com.chanakya.shl2.model.dto.response.MemberShlSummary;
import com.chanakya.shl2.model.enums.AccessType;
import com.chanakya.shl2.model.enums.ShlStatus;
import com.chanakya.shl2.repository.AccessLogDynamoRepository;
import com.chanakya.shl2.repository.MemberPreferencesRepository;
import com.chanakya.shl2.repository.ShlFileRepository;
import com.chanakya.shl2.repository.ShlRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.stream.Collectors;

@Service
public class MemberService {

    private final ShlRepository shlRepository;
    private final ShlFileRepository fileRepository;
    private final MemberPreferencesRepository preferencesRepository;
    private final AccessLogService accessLogService;
    private final S3StorageService s3StorageService;
    private final AccessLogDynamoRepository accessLogDynamoRepository;

    public MemberService(ShlRepository shlRepository,
                         ShlFileRepository fileRepository,
                         MemberPreferencesRepository preferencesRepository,
                         AccessLogService accessLogService,
                         S3StorageService s3StorageService,
                         AccessLogDynamoRepository accessLogDynamoRepository) {
        this.shlRepository = shlRepository;
        this.fileRepository = fileRepository;
        this.preferencesRepository = preferencesRepository;
        this.accessLogService = accessLogService;
        this.s3StorageService = s3StorageService;
        this.accessLogDynamoRepository = accessLogDynamoRepository;
    }

    public Flux<MemberShlSummary> listShlsForMember(String patientId) {
        return shlRepository.findByPatientId(patientId)
                .flatMap(shl -> fileRepository.findByShlId(shl.getId())
                        .count()
                        .map(fileCount -> new MemberShlSummary(
                                shl.getId(),
                                shl.getLabel(),
                                shl.getStatus(),
                                shl.getFlags(),
                                shl.getExpirationTime(),
                                shl.getCategories() != null
                                        ? shl.getCategories().stream()
                                                .map(Enum::name)
                                                .collect(Collectors.toList())
                                        : null,
                                fileCount,
                                shl.getCreatedAt(),
                                shl.getUpdatedAt()
                        )));
    }

    public Mono<Void> deactivateShl(String patientId, String shlId) {
        return shlRepository.findById(shlId)
                .filter(shl -> patientId.equals(shl.getPatientId()))
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMap(shl -> {
                    shl.setStatus(ShlStatus.REVOKED);
                    shl.setUpdatedAt(Instant.now());
                    return shlRepository.save(shl);
                })
                .flatMap(savedShl -> accessLogService.logAccess(savedShl, null, AccessType.REVOKED));
    }

    public Mono<MemberPreferencesResponse> getPreferences(String patientId) {
        return preferencesRepository.findByPatientId(patientId)
                .map(doc -> new MemberPreferencesResponse(doc.isSharingEnabled(), doc.getUpdatedAt()))
                .defaultIfEmpty(new MemberPreferencesResponse(false, null));
    }

    public Mono<MemberPreferencesResponse> updatePreferences(String patientId,
                                                              UpdateMemberPreferencesRequest request) {
        return preferencesRepository.findByPatientId(patientId)
                .defaultIfEmpty(MemberPreferencesDocument.builder()
                        .patientId(patientId)
                        .createdAt(Instant.now())
                        .build())
                .flatMap(doc -> {
                    doc.setSharingEnabled(request.sharingEnabled());
                    doc.setUpdatedAt(Instant.now());
                    return preferencesRepository.save(doc);
                })
                .flatMap(doc -> accessLogService.logAccess(patientId, null, AccessType.PREFERENCE_CHANGED)
                        .thenReturn(new MemberPreferencesResponse(doc.isSharingEnabled(), doc.getUpdatedAt())));
    }

    public Mono<Boolean> isSharingEnabled(String patientId) {
        return preferencesRepository.findByPatientId(patientId)
                .map(MemberPreferencesDocument::isSharingEnabled)
                .defaultIfEmpty(false);
    }

    public Mono<Void> ensureSharingEnabled(String patientId) {
        return preferencesRepository.findByPatientId(patientId)
                .defaultIfEmpty(MemberPreferencesDocument.builder()
                        .patientId(patientId)
                        .createdAt(Instant.now())
                        .build())
                .flatMap(doc -> {
                    if (doc.isSharingEnabled()) {
                        return Mono.empty();
                    }
                    doc.setSharingEnabled(true);
                    doc.setUpdatedAt(Instant.now());
                    return preferencesRepository.save(doc).then();
                });
    }

    public Mono<Void> deleteAllPatientData(String patientId) {
        return shlRepository.findByPatientId(patientId)
                .flatMap(shl -> s3StorageService.deleteByPrefix("shl-files/" + shl.getId() + "/")
                        .then(fileRepository.deleteByShlId(shl.getId()))
                        .then(shlRepository.delete(shl)))
                .then(accessLogDynamoRepository.deleteByPatientId(patientId))
                .then(preferencesRepository.findByPatientId(patientId)
                        .flatMap(preferencesRepository::delete))
                .then();
    }
}
