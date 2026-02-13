package com.chanakya.shl2.model.document;

import com.chanakya.shl2.model.enums.AccessType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "access_logs")
@CompoundIndex(name = "shlId_accessedAt", def = "{'shlId': 1, 'accessedAt': -1}")
public class AccessLogDocument {

    @Id
    private String id;

    @Indexed
    private String patientId;

    private String shlId;
    private String manifestId;
    private String recipient;
    private AccessType accessType;
    private Instant accessedAt;
}
