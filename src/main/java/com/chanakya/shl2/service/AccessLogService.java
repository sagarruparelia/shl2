package com.chanakya.shl2.service;

import com.chanakya.shl2.exception.ShlNotFoundException;
import com.chanakya.shl2.model.document.AccessLogDocument;
import com.chanakya.shl2.model.document.ShlDocument;
import com.chanakya.shl2.model.dto.response.AccessLogEntry;
import com.chanakya.shl2.model.enums.AccessType;
import com.chanakya.shl2.repository.AccessLogRepository;
import com.chanakya.shl2.repository.ShlRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@Service
public class AccessLogService {

    private final AccessLogRepository accessLogRepository;
    private final ShlRepository shlRepository;

    public AccessLogService(AccessLogRepository accessLogRepository, ShlRepository shlRepository) {
        this.accessLogRepository = accessLogRepository;
        this.shlRepository = shlRepository;
    }

    public Mono<Void> logAccess(ShlDocument shl, String recipient, AccessType accessType) {
        AccessLogDocument log = AccessLogDocument.builder()
                .patientId(shl.getPatientId())
                .shlId(shl.getId())
                .manifestId(shl.getManifestId())
                .recipient(recipient)
                .accessType(accessType)
                .accessedAt(Instant.now())
                .build();

        return accessLogRepository.save(log)
                .then()
                .onErrorComplete();
    }

    public Flux<AccessLogEntry> getAccessLogForMember(String patientId) {
        return shlRepository.findByPatientId(patientId)
                .collectMap(ShlDocument::getId, ShlDocument::getLabel)
                .flatMapMany(labelMap ->
                        accessLogRepository.findByPatientIdOrderByAccessedAtDesc(patientId)
                                .map(log -> toEntry(log, labelMap)));
    }

    public Flux<AccessLogEntry> getAccessLogForShl(String patientId, String shlId) {
        return shlRepository.findById(shlId)
                .filter(shl -> patientId.equals(shl.getPatientId()))
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMapMany(shl ->
                        accessLogRepository.findByShlIdOrderByAccessedAtDesc(shlId)
                                .map(log -> toEntry(log, Map.of(shl.getId(), shl.getLabel()))));
    }

    private AccessLogEntry toEntry(AccessLogDocument log, Map<String, String> labelMap) {
        return new AccessLogEntry(
                log.getId(),
                log.getShlId(),
                labelMap.getOrDefault(log.getShlId(), null),
                log.getRecipient(),
                log.getAccessType(),
                log.getAccessedAt()
        );
    }
}
