package com.chanakya.shl2.model.document;

import com.chanakya.shl2.model.enums.FhirCategory;
import com.chanakya.shl2.model.enums.ShlFlag;
import com.chanakya.shl2.model.enums.ShlStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shls")
public class ShlDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String manifestId;

    @Indexed(unique = true)
    private String managementToken;

    private String encryptionKeyBase64;
    private String label;
    private Instant expirationTime;

    private Set<ShlFlag> flags;
    private ShlStatus status;

    private String passcodeHash;
    private Integer passcodeFailuresRemaining;
    private Instant passcodeLockedUntil;

    @Indexed
    private String patientId;
    private List<FhirCategory> categories;
    private Instant timeframeStart;
    private Instant timeframeEnd;

    private boolean includeHealthCards;

    private Instant createdAt;
    private Instant updatedAt;
}
