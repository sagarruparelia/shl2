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
@Document(collection = "shl_files")
public class ShlFileDocument {

    @Id
    private String id;

    @Indexed
    private String shlId;

    private String contentType;
    private String s3Key;
    private long contentLength;

    private Instant lastUpdated;
    private Instant createdAt;
}
