package com.chanakya.shl2.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "member_preferences")
public class MemberPreferencesDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String patientId;

    private boolean sharingEnabled;

    private Instant createdAt;
    private Instant updatedAt;
}
