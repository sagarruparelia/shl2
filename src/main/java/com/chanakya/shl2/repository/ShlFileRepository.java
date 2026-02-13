package com.chanakya.shl2.repository;

import com.chanakya.shl2.model.document.ShlFileDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ShlFileRepository extends ReactiveMongoRepository<ShlFileDocument, String> {

    Flux<ShlFileDocument> findByShlId(String shlId);

    Mono<Void> deleteByShlId(String shlId);
}
