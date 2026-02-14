package com.chanakya.shl2.repository;

import com.chanakya.shl2.config.ShlProperties;
import com.chanakya.shl2.model.dynamodb.AccessLogItem;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.HashMap;
import java.util.Map;

@Repository
public class AccessLogDynamoRepository {

    private final DynamoDbAsyncClient dynamoClient;
    private final String tableName;

    public AccessLogDynamoRepository(DynamoDbAsyncClient dynamoClient, ShlProperties properties) {
        this.dynamoClient = dynamoClient;
        this.tableName = properties.aws().dynamoAccessLogTable();
    }

    public Mono<Void> save(AccessLogItem item) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item.toItem())
                .build();

        return Mono.fromFuture(dynamoClient.putItem(request)).then();
    }

    public Flux<AccessLogItem> findByPatientId(String patientId) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("patientId = :pid")
                .expressionAttributeValues(Map.of(
                        ":pid", AttributeValue.fromS(patientId)
                ))
                .scanIndexForward(false)
                .build();

        return Flux.from(dynamoClient.queryPaginator(request))
                .flatMapIterable(response -> response.items())
                .map(AccessLogItem::fromItem);
    }

    public Mono<software.amazon.awssdk.services.dynamodb.model.QueryResponse> findByPatientIdPaginated(
            String patientId, int limit, String cursor) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("patientId = :pid")
                .expressionAttributeValues(Map.of(
                        ":pid", AttributeValue.fromS(patientId)
                ))
                .scanIndexForward(false)
                .limit(limit);

        if (cursor != null && !cursor.isBlank()) {
            Map<String, AttributeValue> exclusiveStartKey = new HashMap<>();
            exclusiveStartKey.put("patientId", AttributeValue.fromS(patientId));
            exclusiveStartKey.put("sortKey", AttributeValue.fromS(cursor));
            builder.exclusiveStartKey(exclusiveStartKey);
        }

        return Mono.fromFuture(dynamoClient.query(builder.build()));
    }

    public Mono<Void> deleteByPatientId(String patientId) {
        return findByPatientId(patientId)
                .flatMap(item -> {
                    DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
                            .tableName(tableName)
                            .key(Map.of(
                                    "patientId", AttributeValue.fromS(item.patientId()),
                                    "sortKey", AttributeValue.fromS(item.sortKey())
                            ))
                            .build();
                    return Mono.fromFuture(dynamoClient.deleteItem(deleteRequest));
                })
                .then();
    }

    public Flux<AccessLogItem> findByShlId(String shlId) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .indexName("shlId-index")
                .keyConditionExpression("shlId = :sid")
                .expressionAttributeValues(Map.of(
                        ":sid", AttributeValue.fromS(shlId)
                ))
                .scanIndexForward(false)
                .build();

        return Flux.from(dynamoClient.queryPaginator(request))
                .flatMapIterable(response -> response.items())
                .map(AccessLogItem::fromItem);
    }
}
