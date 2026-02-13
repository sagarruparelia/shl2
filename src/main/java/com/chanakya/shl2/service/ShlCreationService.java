package com.chanakya.shl2.service;

import com.chanakya.shl2.config.ShlProperties;
import com.chanakya.shl2.crypto.JweService;
import com.chanakya.shl2.crypto.KeyGenerationService;
import com.chanakya.shl2.crypto.ShlPayloadEncoder;
import com.chanakya.shl2.exception.ShlNotFoundException;
import com.chanakya.shl2.model.document.ShlDocument;
import com.chanakya.shl2.model.document.ShlFileDocument;
import com.chanakya.shl2.model.dto.request.CreateShlRequest;
import com.chanakya.shl2.model.dto.response.CreateShlResponse;
import com.chanakya.shl2.model.dto.response.ShlStatusResponse;
import com.chanakya.shl2.model.enums.AccessType;
import com.chanakya.shl2.model.enums.ShlFlag;
import com.chanakya.shl2.model.enums.ShlStatus;
import com.chanakya.shl2.repository.ShlFileRepository;
import com.chanakya.shl2.repository.ShlRepository;
import com.chanakya.shl2.util.EntropyUtil;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class ShlCreationService {

    private final ShlRepository shlRepository;
    private final ShlFileRepository fileRepository;
    private final KeyGenerationService keyGenerationService;
    private final JweService jweService;
    private final ShlPayloadEncoder payloadEncoder;
    private final PasscodeService passcodeService;
    private final HealthLakeService healthLakeService;
    private final SmartHealthCardService shcService;
    private final QrCodeService qrCodeService;
    private final S3StorageService s3StorageService;
    private final AccessLogService accessLogService;
    private final ShlProperties properties;

    public ShlCreationService(ShlRepository shlRepository,
                              ShlFileRepository fileRepository,
                              KeyGenerationService keyGenerationService,
                              JweService jweService,
                              ShlPayloadEncoder payloadEncoder,
                              PasscodeService passcodeService,
                              HealthLakeService healthLakeService,
                              SmartHealthCardService shcService,
                              QrCodeService qrCodeService,
                              S3StorageService s3StorageService,
                              AccessLogService accessLogService,
                              ShlProperties properties) {
        this.shlRepository = shlRepository;
        this.fileRepository = fileRepository;
        this.keyGenerationService = keyGenerationService;
        this.jweService = jweService;
        this.payloadEncoder = payloadEncoder;
        this.passcodeService = passcodeService;
        this.healthLakeService = healthLakeService;
        this.shcService = shcService;
        this.qrCodeService = qrCodeService;
        this.s3StorageService = s3StorageService;
        this.accessLogService = accessLogService;
        this.properties = properties;
    }

    /**
     * Creates a new SHL end-to-end.
     */
    public Mono<CreateShlResponse> createShl(CreateShlRequest request) {
        String encryptionKey = keyGenerationService.generateAes256Key();
        String manifestId = EntropyUtil.generateManifestId();
        String managementToken = EntropyUtil.generateManagementToken();

        // Build flags
        Set<ShlFlag> flags = new HashSet<>();
        if (request.flags() != null) {
            flags.addAll(request.flags());
        }
        if (request.passcode() != null && !request.passcode().isBlank()) {
            flags.add(ShlFlag.P);
        }

        // Per SHL spec: U and P flags are mutually exclusive
        if (flags.contains(ShlFlag.U) && flags.contains(ShlFlag.P)) {
            return Mono.error(new IllegalStateException("U and P flags cannot be combined"));
        }

        // Build SHL document
        Instant now = Instant.now();
        ShlDocument shl = ShlDocument.builder()
                .manifestId(manifestId)
                .managementToken(managementToken)
                .encryptionKeyBase64(encryptionKey)
                .label(request.label())
                .expirationTime(request.timeframeEnd())
                .flags(flags)
                .status(ShlStatus.ACTIVE)
                .passcodeHash(request.passcode() != null && !request.passcode().isBlank()
                        ? passcodeService.hashPasscode(request.passcode()) : null)
                .passcodeFailuresRemaining(request.passcode() != null && !request.passcode().isBlank()
                        ? properties.defaultPasscodeAttempts() : null)
                .patientId(request.patientId())
                .categories(request.categories())
                .timeframeStart(request.timeframeStart())
                .timeframeEnd(request.timeframeEnd())
                .includeHealthCards(request.includeHealthCards())
                .createdAt(now)
                .updatedAt(now)
                .build();

        return shlRepository.save(shl)
                .flatMap(savedShl -> accessLogService.logAccess(savedShl, null, AccessType.CREATED)
                        .then(fetchAndEncryptData(savedShl))
                        .thenReturn(savedShl))
                .flatMap(savedShl -> {
                    String shlUri = payloadEncoder.encode(savedShl);

                    if (request.generateQrCode()) {
                        return qrCodeService.generateQrCodeDataUri(shlUri, 400)
                                .map(qrDataUri -> new CreateShlResponse(
                                        shlUri,
                                        savedShl.getManagementToken(),
                                        qrDataUri,
                                        savedShl.getExpirationTime(),
                                        savedShl.getLabel()
                                ));
                    }

                    return Mono.just(new CreateShlResponse(
                            shlUri,
                            savedShl.getManagementToken(),
                            null,
                            savedShl.getExpirationTime(),
                            savedShl.getLabel()
                    ));
                });
    }

    /**
     * Gets the status of an SHL by management token.
     */
    public Mono<ShlStatusResponse> getStatus(String managementToken) {
        return shlRepository.findByManagementToken(managementToken)
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMap(shl -> fileRepository.findByShlId(shl.getId())
                        .count()
                        .map(count -> new ShlStatusResponse(
                                shl.getManifestId(),
                                shl.getLabel(),
                                shl.getStatus(),
                                shl.getFlags(),
                                shl.getExpirationTime(),
                                count,
                                shl.getCreatedAt()
                        )));
    }

    /**
     * Revokes an SHL by management token.
     */
    public Mono<Void> revokeShl(String managementToken) {
        return shlRepository.findByManagementToken(managementToken)
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMap(shl -> {
                    shl.setStatus(ShlStatus.REVOKED);
                    shl.setUpdatedAt(Instant.now());
                    return shlRepository.save(shl);
                })
                .flatMap(savedShl -> accessLogService.logAccess(savedShl, null, AccessType.REVOKED));
    }

    /**
     * Refreshes SHL data for L-flag links by re-fetching from HealthLake.
     */
    public Mono<Void> refreshShlData(String managementToken) {
        return shlRepository.findByManagementToken(managementToken)
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMap(shl -> {
                    if (!shl.getFlags().contains(ShlFlag.L)) {
                        return Mono.error(new IllegalStateException("Only long-term SHLs can be refreshed"));
                    }
                    return s3StorageService.deleteByPrefix("shl-files/" + shl.getId() + "/")
                            .then(fileRepository.deleteByShlId(shl.getId()))
                            .then(fetchAndEncryptData(shl))
                            .then(Mono.defer(() -> {
                                shl.setUpdatedAt(Instant.now());
                                return shlRepository.save(shl);
                            }))
                            .flatMap(savedShl -> accessLogService.logAccess(savedShl, null, AccessType.REFRESHED));
                });
    }

    /**
     * Generates a QR code for an existing SHL.
     */
    public Mono<byte[]> generateQrCode(String managementToken) {
        return shlRepository.findByManagementToken(managementToken)
                .switchIfEmpty(Mono.error(new ShlNotFoundException("SHL not found")))
                .flatMap(shl -> {
                    String shlUri = payloadEncoder.encode(shl);
                    return qrCodeService.generateQrCode(shlUri, 400);
                });
    }

    private Mono<Void> fetchAndEncryptData(ShlDocument shl) {
        String fhirContentType = "application/fhir+json;fhirVersion=4.0.1";

        if (shl.getFlags().contains(ShlFlag.U)) {
            // U-flag: spec requires single encrypted file — merge all bundles into one
            return healthLakeService.fetchResourcesByCategory(
                            shl.getPatientId(),
                            shl.getCategories(),
                            shl.getTimeframeStart(),
                            shl.getTimeframeEnd()
                    )
                    .map(wrapper -> wrapper.getBundleJson())
                    .collectList()
                    .flatMap(bundles -> {
                        String merged = mergeFhirBundles(bundles);
                        String encrypted = jweService.encrypt(merged, shl.getEncryptionKeyBase64(), fhirContentType);
                        String s3Key = "shl-files/" + shl.getId() + "/" + UUID.randomUUID();
                        return s3StorageService.upload(s3Key, encrypted, shl.getExpirationTime())
                                .flatMap(key -> {
                                    ShlFileDocument fileDoc = ShlFileDocument.builder()
                                            .shlId(shl.getId())
                                            .contentType(fhirContentType)
                                            .s3Key(key)
                                            .contentLength(encrypted.getBytes(StandardCharsets.UTF_8).length)
                                            .lastUpdated(Instant.now())
                                            .createdAt(Instant.now())
                                            .build();
                                    return fileRepository.save(fileDoc);
                                });
                    })
                    .then();
        }

        return healthLakeService.fetchResourcesByCategory(
                        shl.getPatientId(),
                        shl.getCategories(),
                        shl.getTimeframeStart(),
                        shl.getTimeframeEnd()
                )
                .flatMap(wrapper -> {
                    String encrypted = jweService.encrypt(wrapper.getBundleJson(), shl.getEncryptionKeyBase64(), fhirContentType);
                    String s3Key = "shl-files/" + shl.getId() + "/" + UUID.randomUUID();
                    return s3StorageService.upload(s3Key, encrypted, shl.getExpirationTime())
                            .flatMap(key -> {
                                ShlFileDocument fileDoc = ShlFileDocument.builder()
                                        .shlId(shl.getId())
                                        .contentType(fhirContentType)
                                        .s3Key(key)
                                        .contentLength(encrypted.getBytes(StandardCharsets.UTF_8).length)
                                        .lastUpdated(Instant.now())
                                        .createdAt(Instant.now())
                                        .build();
                                return fileRepository.save(fileDoc);
                            });
                })
                .then(shl.isIncludeHealthCards()
                        ? createHealthCards(shl)
                        : Mono.empty())
                .then();
    }

    /**
     * Merges multiple FHIR Bundle JSONs into a single Bundle for U-flag SHLs.
     */
    private String mergeFhirBundles(java.util.List<String> bundles) {
        if (bundles.size() == 1) {
            return bundles.getFirst();
        }
        try {
            var objectMapper = new tools.jackson.databind.ObjectMapper();
            var merged = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(bundles.getFirst());
            var entries = merged.has("entry")
                    ? (tools.jackson.databind.node.ArrayNode) merged.get("entry")
                    : merged.putArray("entry");

            for (int i = 1; i < bundles.size(); i++) {
                var other = objectMapper.readTree(bundles.get(i));
                var otherEntries = other.path("entry");
                if (otherEntries.isArray()) {
                    for (var entry : otherEntries) {
                        entries.add(entry);
                    }
                }
            }
            merged.put("total", entries.size());
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            throw new RuntimeException("Failed to merge FHIR bundles for U-flag SHL", e);
        }
    }

    private Mono<Void> createHealthCards(ShlDocument shl) {
        return healthLakeService.fetchResourcesByCategory(
                        shl.getPatientId(),
                        shl.getCategories(),
                        shl.getTimeframeStart(),
                        shl.getTimeframeEnd()
                )
                .flatMap(wrapper -> shcService.createHealthCard(wrapper.getBundleJson())
                        .flatMap(shcJson -> {
                            String shcContentType = "application/smart-health-card";
                            String encrypted = jweService.encrypt(shcJson, shl.getEncryptionKeyBase64(), shcContentType);
                            String s3Key = "shl-files/" + shl.getId() + "/" + UUID.randomUUID();
                            return s3StorageService.upload(s3Key, encrypted, shl.getExpirationTime())
                                    .flatMap(key -> {
                                        ShlFileDocument fileDoc = ShlFileDocument.builder()
                                                .shlId(shl.getId())
                                                .contentType(shcContentType)
                                                .s3Key(key)
                                                .contentLength(encrypted.getBytes(StandardCharsets.UTF_8).length)
                                                .lastUpdated(Instant.now())
                                                .createdAt(Instant.now())
                                                .build();
                                        return fileRepository.save(fileDoc);
                                    });
                        }))
                .then();
    }
}
