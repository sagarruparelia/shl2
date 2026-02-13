package com.chanakya.shl2.repository;

import com.chanakya.shl2.model.document.MemberPreferencesDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

public interface MemberPreferencesRepository extends ReactiveMongoRepository<MemberPreferencesDocument, String> {

    Mono<MemberPreferencesDocument> findByPatientId(String patientId);
}
