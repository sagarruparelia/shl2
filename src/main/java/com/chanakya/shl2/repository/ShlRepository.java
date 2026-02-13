package com.chanakya.shl2.repository;

import com.chanakya.shl2.model.document.ShlDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

public interface ShlRepository extends ReactiveMongoRepository<ShlDocument, String>, ShlRepositoryCustom {

    Mono<ShlDocument> findByManifestId(String manifestId);

    Mono<ShlDocument> findByManagementToken(String managementToken);
}
