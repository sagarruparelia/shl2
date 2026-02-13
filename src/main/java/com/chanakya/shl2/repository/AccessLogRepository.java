package com.chanakya.shl2.repository;

import com.chanakya.shl2.model.document.AccessLogDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

public interface AccessLogRepository extends ReactiveMongoRepository<AccessLogDocument, String> {

    Flux<AccessLogDocument> findByPatientIdOrderByAccessedAtDesc(String patientId);

    Flux<AccessLogDocument> findByShlIdOrderByAccessedAtDesc(String shlId);
}
