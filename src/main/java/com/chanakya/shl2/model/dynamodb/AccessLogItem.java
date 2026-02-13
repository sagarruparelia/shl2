package com.chanakya.shl2.model.dynamodb;

import com.chanakya.shl2.model.enums.AccessType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record AccessLogItem(
        String patientId,
        String sortKey,
        String id,
        String shlId,
        String manifestId,
        String recipient,
        AccessType accessType,
        Instant accessedAt
) {

    public static String buildSortKey(Instant timestamp, String uuid) {
        return timestamp.toString() + "#" + uuid;
    }

    public Map<String, AttributeValue> toItem() {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("patientId", AttributeValue.fromS(patientId));
        item.put("sortKey", AttributeValue.fromS(sortKey));
        item.put("id", AttributeValue.fromS(id));
        item.put("shlId", AttributeValue.fromS(shlId));
        item.put("manifestId", AttributeValue.fromS(manifestId));
        item.put("accessType", AttributeValue.fromS(accessType.name()));
        item.put("accessedAt", AttributeValue.fromS(accessedAt.toString()));
        if (recipient != null) {
            item.put("recipient", AttributeValue.fromS(recipient));
        }
        return item;
    }

    public static AccessLogItem fromItem(Map<String, AttributeValue> item) {
        return new AccessLogItem(
                item.get("patientId").s(),
                item.get("sortKey").s(),
                item.get("id").s(),
                item.get("shlId").s(),
                item.get("manifestId").s(),
                item.containsKey("recipient") ? item.get("recipient").s() : null,
                AccessType.valueOf(item.get("accessType").s()),
                Instant.parse(item.get("accessedAt").s())
        );
    }
}
