package com.chanakya.shl2.service;

import com.chanakya.shl2.exception.ShlNotFoundException;
import com.chanakya.shl2.model.document.MemberPreferencesDocument;
import com.chanakya.shl2.model.dto.request.UpdateMemberPreferencesRequest;
import com.chanakya.shl2.model.dto.response.MemberPreferencesResponse;
import com.chanakya.shl2.model.dto.response.MemberShlSummary;
import com.chanakya.shl2.model.enums.ShlStatus;
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

    public MemberService(ShlRepository shlRepository,
                         ShlFileRepository fileRepository,
                         MemberPreferencesRepository preferencesRepository) {
        this.shlRepository = shlRepository;
        this.fileRepository = fileRepository;
        this.preferencesRepository = preferencesRepository;
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
                .then();
    }

    public Mono<MemberPreferencesResponse> getPreferences(String patientId) {
        return preferencesRepository.findByPatientId(patientId)
                .map(doc -> new MemberPreferencesResponse(doc.isSharingEnabled(), doc.getUpdatedAt()))
                .defaultIfEmpty(new MemberPreferencesResponse(true, null));
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
                .map(doc -> new MemberPreferencesResponse(doc.isSharingEnabled(), doc.getUpdatedAt()));
    }

    public Mono<Boolean> isSharingEnabled(String patientId) {
        return preferencesRepository.findByPatientId(patientId)
                .map(MemberPreferencesDocument::isSharingEnabled)
                .defaultIfEmpty(true);
    }
}
