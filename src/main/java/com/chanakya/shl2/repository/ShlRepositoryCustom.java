package com.chanakya.shl2.repository;

import com.chanakya.shl2.model.document.ShlDocument;
import reactor.core.publisher.Mono;

public interface ShlRepositoryCustom {

    Mono<ShlDocument> decrementPasscodeAttempts(String manifestId);
}
