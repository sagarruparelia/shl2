package com.chanakya.shl2.service;

import com.chanakya.shl2.exception.ShlNotFoundException;
import com.chanakya.shl2.model.document.ShlDocument;
import com.chanakya.shl2.model.dynamodb.AccessLogItem;
import com.chanakya.shl2.model.dto.response.AccessLogEntry;
import com.chanakya.shl2.model.enums.AccessType;
import com.chanakya.shl2.repository.AccessLogDynamoRepository;
import com.chanakya.shl2.repository.ShlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AccessLogService {

    private static final Logger log = LoggerFactory.getLogger(AccessLogService.class);

    private final AccessLogDynamoRepository accessLogRepository;
    private final ShlRepository shlRepository;

    public AccessLogService(AccessLogDynamoRepository accessLogRepository, ShlRepository shlRepository) {
        this.accessLogRepository = accessLogRepository;
        this.shlRepository = shlRepository;
    }

    public Mono<Void> logAccess(ShlDocument shl, String recipient, AccessType accessType) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        AccessLogItem item = new AccessLogItem(
                shl.getPatientId(),
                AccessLogItem.buildSortKey(now, id),
                id,
                shl.getId(),
                shl.getManifestId(),
                recipient,
                accessType,
                now
        );

        return accessLogRepository.save(item)
                .doOnError(e -> log.error("event=access_log_write_failed shlId={} accessType={} error={}",
                        shl.getId(), accessType, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<Void> logAccess(String patientId, String recipient, AccessType accessType) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        AccessLogItem item = new AccessLogItem(
                patientId,
                AccessLogItem.buildSortKey(now, id),
                id,
                null,
                null,
                recipient,
                accessType,
                now
        );

        return accessLogRepository.save(item)
                .doOnError(e -> log.error("event=access_log_write_failed patientId={} accessType={} error={}",
                        patientId, accessType, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    public Flux<AccessLogEntry> getAccessLogForMember(String patientId) {
        return shlRepository.findByPatientId(patientId)
                .collectList()
                .flatMapMany(shls -> {
                    Map<String, String> labelMap = new HashMap<>();
                    shls.forEach(shl -> labelMap.put(shl.getId(), shl.getLabel()));
                    return accessLogRepository.findByPatientId(patientId)
                            .map(item -> toEntry(item, labelMap));
                });
    }

    public Flux<AccessLogEntry> getAccessLogForShl(String patientId, String shlId) {
        return shlRepository.findById(shlId)
                .filter(shl -> patientId.equals(shl.getPatientId()))
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMapMany(shl -> {
                    Map<String, String> labelMap = new HashMap<>();
                    labelMap.put(shl.getId(), shl.getLabel());
                    return accessLogRepository.findByShlId(shlId)
                            .map(item -> toEntry(item, labelMap));
                });
    }

    private AccessLogEntry toEntry(AccessLogItem item, Map<String, String> labelMap) {
        return new AccessLogEntry(
                item.id(),
                item.shlId(),
                labelMap.getOrDefault(item.shlId(), null),
                item.recipient(),
                item.accessType(),
                item.accessedAt()
        );
    }
}
