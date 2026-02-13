package com.chanakya.shl2.repository;

import com.chanakya.shl2.model.document.ShlDocument;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class ShlRepositoryCustomImpl implements ShlRepositoryCustom {

    private final ReactiveMongoTemplate mongoTemplate;

    public ShlRepositoryCustomImpl(ReactiveMongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Mono<ShlDocument> decrementPasscodeAttempts(String manifestId) {
        Query query = Query.query(
                Criteria.where("manifestId").is(manifestId)
                        .and("passcodeFailuresRemaining").gt(0)
        );
        Update update = new Update().inc("passcodeFailuresRemaining", -1);
        return mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ShlDocument.class
        );
    }
}
