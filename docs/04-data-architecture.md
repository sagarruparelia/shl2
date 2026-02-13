# Data Architecture — FHIR Resource Management

## Overview

SHL2 acts as a **FHIR data broker** between AWS HealthLake (FHIR R4 data store) and SMART Health Link consumers. It does not store raw FHIR data — it fetches, encrypts, and serves it through the SHL protocol.

---

## FHIR R4 Resource Categories

### Supported Resource Types

| Category Enum | FHIR Resource | Search Endpoint | Search Parameters |
|---|---|---|---|
| `PATIENT_DEMOGRAPHICS` | Patient | `GET /r4/Patient/{id}` | Direct read (no search) |
| `CONDITIONS` | Condition | `GET /r4/Condition?...` | `patient={id}` |
| `MEDICATIONS` | MedicationRequest | `GET /r4/MedicationRequest?...` | `patient={id}` |
| `LAB_RESULTS` | Observation | `GET /r4/Observation?...` | `patient={id}&category=laboratory` |
| `VITAL_SIGNS` | Observation | `GET /r4/Observation?...` | `patient={id}&category=vital-signs` |
| `IMMUNIZATIONS` | Immunization | `GET /r4/Immunization?...` | `patient={id}` |
| `ALLERGIES` | AllergyIntolerance | `GET /r4/AllergyIntolerance?...` | `patient={id}` |
| `PROCEDURES` | Procedure | `GET /r4/Procedure?...` | `patient={id}` |
| `DIAGNOSTIC_REPORTS` | DiagnosticReport | `GET /r4/DiagnosticReport?...` | `patient={id}` |
| `ENCOUNTERS` | Encounter | `GET /r4/Encounter?...` | `patient={id}` |
| `CLINICAL_DOCUMENTS` | DocumentReference | `GET /r4/DocumentReference?...` | `patient={id}&category=clinical-note` |

### Date Filtering

When `timeframeStart` and/or `timeframeEnd` are specified, they are appended as FHIR date search parameters:

```
GET /r4/Condition?patient=123&date=ge2024-01-01T00:00:00Z&date=le2025-12-31T23:59:59Z
```

Date filtering applies to all searchable categories. `PATIENT_DEMOGRAPHICS` (direct read) is not date-filtered.

---

## Data Formats

### Input Format (from HealthLake)

All data comes from HealthLake as **FHIR R4 JSON** (`application/fhir+json`). Resources conform to the HL7 FHIR R4 specification (v4.0.1).

Example Condition resource:
```json
{
  "resourceType": "Condition",
  "id": "cond-456",
  "meta": {
    "lastUpdated": "2024-06-15T10:30:00.000Z",
    "versionId": "1"
  },
  "clinicalStatus": {
    "coding": [{
      "system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
      "code": "active",
      "display": "Active"
    }]
  },
  "code": {
    "coding": [{
      "system": "http://snomed.info/sct",
      "code": "44054006",
      "display": "Diabetes mellitus type 2"
    }],
    "text": "Type 2 Diabetes"
  },
  "subject": {
    "reference": "Patient/patient-123"
  },
  "onsetDateTime": "2020-03-15"
}
```

### Storage Format (S3 + MongoDB)

FHIR data is **never stored as plaintext**. The flow:

```
FHIR Bundle (JSON)
  -> JWE encrypt (AES-256-GCM, with cty header)
  -> Upload to S3 (key: shl-files/{shlId}/{uuid})
  -> Save metadata to MongoDB (s3Key + contentLength, no content)
```

The `ShlFileDocument` schema (MongoDB — metadata only):

```json
{
  "_id": "ObjectId",
  "shlId": "reference to ShlDocument._id",
  "contentType": "application/fhir+json;fhirVersion=4.0.1",
  "s3Key": "shl-files/abc123/550e8400-e29b-41d4-a716-446655440000",
  "contentLength": 34000,
  "lastUpdated": "2025-01-15T10:30:00Z",
  "createdAt": "2025-01-15T10:30:00Z"
}
```

The encrypted content (JWE compact serialization) is stored in S3 with `application/jose` content type and SSE-S3 encryption.

### Output Formats (to consumers)

Two content types are produced:

#### 1. `application/fhir+json;fhirVersion=4.0.1`

Raw FHIR Bundle containing the selected resources. One bundle per category (non-U-flag) or one merged bundle (U-flag).

Bundle structure:
```json
{
  "resourceType": "Bundle",
  "type": "searchset",
  "total": 5,
  "entry": [
    {
      "resource": { "resourceType": "Condition", ... }
    },
    {
      "resource": { "resourceType": "Condition", ... }
    }
  ]
}
```

#### 2. `application/smart-health-card`

Signed, verifiable FHIR data as a SMART Health Card. The data inside is a **minified** FHIR Bundle:

```json
{
  "verifiableCredential": [
    "eyJhbGciOiJFUzI1NiIsInppcCI6IkRFRiIsImtpZCI6IjNLZmRnLVh3UC03Z..."
  ]
}
```

Inside the JWS payload (after decompression):
```json
{
  "iss": "https://shl.example.com",
  "nbf": 1705312200,
  "vc": {
    "type": ["https://smarthealth.cards#health-card"],
    "credentialSubject": {
      "fhirVersion": "4.0.1",
      "fhirBundle": {
        "resourceType": "Bundle",
        "type": "collection",
        "entry": [
          {
            "fullUrl": "resource:0",
            "resource": { "resourceType": "Patient", ... }
          }
        ]
      }
    }
  }
}
```

---

## Data Transformations

### FHIR Bundle Wrapping

Individual resources fetched from HealthLake are wrapped in a FHIR Bundle:

```
Patient/123 (direct read)  ->  Bundle { type: "searchset", entry: [{ resource: Patient }] }
```

Search results are already Bundles from HealthLake and are used directly (with pagination merged).

### Pagination Handling

HealthLake returns paginated results via `Bundle.link` with `relation: "next"`. The system:

1. Fetches the initial search result
2. Follows all `next` links
3. Merges all pages into a single Bundle
4. Removes pagination `link` entries
5. Updates the `total` count

### DocumentReference Binary Resolution

For `CLINICAL_DOCUMENTS`, DocumentReference resources may reference Binary resources containing actual document content (PDFs, images, etc.):

```
DocumentReference.content[].attachment.url = "Binary/abc123"
```

The system:
1. Detects relative Binary references matching pattern `Binary/[\\w-]+`
2. Fetches the Binary resource from HealthLake
3. Embeds the `data` (base64) and `contentType` into the attachment
4. Removes the `url` field (now resolved)

If the Binary fetch fails, the original reference is preserved for the consumer to resolve independently.

**Future enhancement**: For large binary content (PDFs >1MB), store as separate ShlFileDocuments with native content type rather than embedding in the FHIR Bundle.

### SHC FHIR Minification

When generating SMART Health Cards, the FHIR Bundle is minified per the SHC spec to reduce QR code size:

| Transformation | Before | After |
|---|---|---|
| `Resource.id` | `"id": "cond-456"` | (removed) |
| `Resource.meta` | `"meta": {"lastUpdated": "...", "versionId": "1"}` | (removed, unless only `security` present) |
| `DomainResource.text` | `"text": {"status": "generated", "div": "..."}` | (removed) |
| `CodeableConcept.text` | `"text": "Type 2 Diabetes"` | (removed) |
| `Coding.display` | `"display": "Active"` | (removed) |
| `Bundle.entry.fullUrl` | `"fullUrl": "urn:uuid:..."` | `"fullUrl": "resource:0"` |
| `Reference.reference` | `"reference": "Patient/123"` | `"reference": "resource:0"` |

---

## MongoDB Data Model

### `shls` Collection

```
{
  _id:                      ObjectId (auto)
  manifestId:               String  (unique index, 43-char base64url)
  managementToken:          String  (unique index, UUID)
  encryptionKeyBase64:      String  (43-char base64url AES-256 key)
  label:                    String  (max 80 chars, nullable)
  expirationTime:           ISODate (nullable)
  flags:                    Array<String>  (["L"], ["P"], ["L","P"], ["U"])
  status:                   String  (ACTIVE | REVOKED | EXPIRED)
  passcodeHash:             String  (BCrypt hash, nullable)
  passcodeFailuresRemaining: Integer (nullable, decremented atomically)
  patientId:                String
  categories:               Array<String>  (enum values)
  timeframeStart:           ISODate (nullable)
  timeframeEnd:             ISODate (nullable)
  includeHealthCards:        Boolean
  createdAt:                ISODate
  updatedAt:                ISODate
}
```

### `shl_files` Collection

```
{
  _id:            ObjectId (auto)
  shlId:          String  (indexed, references shls._id)
  contentType:    String  ("application/fhir+json;fhirVersion=4.0.1" | "application/smart-health-card")
  s3Key:          String  (S3 object key, e.g. "shl-files/{shlId}/{uuid}")
  contentLength:  Long    (byte length of encrypted content in S3)
  lastUpdated:    ISODate
  createdAt:      ISODate
}
```

Note: Encrypted content is no longer stored in MongoDB. The `s3Key` field points to the S3 object containing the JWE compact serialization. `contentLength` enables the embed-vs-presign decision without an S3 HEAD request.

---

## S3 Storage — `shl2-files` Bucket

### Object Structure

```
shl-files/
  {shlId}/
    {uuid-1}    <- JWE encrypted FHIR bundle (application/jose)
    {uuid-2}    <- JWE encrypted SHC (application/jose)
    ...
```

### Configuration

| Setting | Value |
|---|---|
| Bucket name | Configurable via `SHL_S3_BUCKET` (default: `shl2-files`) |
| Encryption | SSE-S3 (defense in depth — content is already JWE-encrypted) |
| Public access | Blocked (all access via presigned URLs) |
| Versioning | Disabled |
| Content type | `application/jose` |

### Lifecycle Rules

```
Rule: "archive-expired-files"
  Filter:     Tag "expirationDate" exists
  Transition: S3 Glacier Flexible Retrieval after expiration date
```

Files are tagged with `expirationDate` at upload time (when the SHL has an expiration). S3 lifecycle rules automatically transition expired files to Glacier for cold storage.

### Access Pattern

All file access from consumers goes through S3 presigned URLs (1-hour expiry). The SHL server never proxies file content — it only generates presigned URLs and returns them in manifest responses.

---

## DynamoDB — `shl2-access-logs` Table

### Schema

```
Table: shl2-access-logs
  Partition Key:  patientId (String)
  Sort Key:       sortKey (String) — format: "ISO-8601#UUID"

GSI: shlId-index
  Partition Key:  shlId (String)
  Sort Key:       accessedAt (String)
  Projection:     ALL
```

### Item Structure

```json
{
  "patientId": "patient-123",
  "sortKey": "2025-01-15T10:30:00Z#550e8400-e29b-41d4-a716-446655440000",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "shlId": "abc123",
  "manifestId": "def456...",
  "recipient": "Dr. Smith",
  "accessType": "MANIFEST",
  "accessedAt": "2025-01-15T10:30:00Z"
}
```

### Configuration

| Setting | Value |
|---|---|
| Table name | Configurable via `SHL_DYNAMO_ACCESS_LOG_TABLE` (default: `shl2-access-logs`) |
| Table class | Standard-Infrequent Access (60% cheaper storage) |
| Billing mode | Pay-per-request |
| PITR | Enabled (compliance) |
| TTL | None (10-year CMS retention) |
| Streams | Enabled (NEW_IMAGE) for future analytics pipeline |

### Query Patterns

| Operation | Access Pattern | Key Used |
|---|---|---|
| Log access event | PutItem | `patientId` + `sortKey` |
| Member access log | Query on table, `scanIndexForward=false` | `patientId` (descending) |
| Per-SHL access log | Query on `shlId-index` GSI | `shlId` (descending by `accessedAt`) |

### Sizing Estimates

At 5M patients / 1B log entries:
- Storage: ~$20/mo (Standard-IA)
- Writes: Pay-per-request, ~$1.25 per million writes
- Reads: Pay-per-request, ~$0.25 per million reads

### Sizing Estimates

| Data Type | Typical Unencrypted Size | Encrypted (JWE overhead ~100 bytes) |
|---|---|---|
| Conditions Bundle (10 conditions) | ~5 KB | ~7 KB |
| Medications Bundle (15 meds) | ~8 KB | ~11 KB |
| Lab Results Bundle (50 observations) | ~25 KB | ~34 KB |
| Clinical Documents (with embedded PDFs) | 1-10 MB per document | 1.3-13 MB |
| Smart Health Card | ~2-5 KB | ~3-7 KB |

### Query Patterns

| Operation | Query | Store | Index Used |
|---|---|---|---|
| Manifest lookup | `findByManifestId(id)` | MongoDB | `manifestId` (unique) |
| Management lookup | `findByManagementToken(token)` | MongoDB | `managementToken` (unique) |
| File metadata listing | `findByShlId(shlId)` | MongoDB | `shlId` |
| File content download | `GetObject(s3Key)` | S3 | S3 key |
| File content presign | `presignGetObject(s3Key)` | S3 | S3 key |
| Passcode decrement | `findAndModify(manifestId, $inc: -1)` | MongoDB | `manifestId` |
| File cleanup | `deleteByShlId(shlId)` + `deleteByPrefix(prefix)` | MongoDB + S3 | `shlId` / S3 prefix |
| Log access | `PutItem(patientId, sortKey)` | DynamoDB | Table PK |
| Member access log | `Query(patientId)` | DynamoDB | Table PK |
| Per-SHL access log | `Query(shlId)` | DynamoDB | `shlId-index` GSI |

---

## HealthLake Integration

### Connection

```
Base URL: https://healthlake.{region}.amazonaws.com/datastore/{datastoreId}/r4
Auth:     AWS SigV4 (DefaultCredentialsProvider)
Format:   application/fhir+json
```

### FHIR Conformance

The system depends on HealthLake supporting these FHIR R4 interactions:

| Interaction | Used By |
|---|---|
| `read` (GET /ResourceType/id) | Patient demographics |
| `search-type` (GET /ResourceType?params) | All other categories |
| `read` Binary (GET /Binary/id) | DocumentReference attachment resolution |

### Search Parameters Used

| Parameter | FHIR Type | Usage |
|---|---|---|
| `patient` | reference | Filter resources by patient |
| `category` | token | Filter Observations (laboratory, vital-signs) and DocumentReferences (clinical-note) |
| `date` | date | Filter by date range (ge/le prefixes) |

---

## Data Lifecycle

```
Creation:
  HealthLake -> Fetch FHIR -> Encrypt -> S3 (JWE content) + MongoDB (metadata)

Access (embed path):
  MongoDB (file metadata) -> check contentLength -> S3 download -> embed in response

Access (presign path):
  MongoDB (file metadata) -> generate S3 presigned URL -> consumer fetches from S3 directly

Refresh (L-flag):
  Delete S3 objects by prefix -> Delete MongoDB shl_files -> HealthLake -> Encrypt -> S3 + MongoDB

Revocation:
  Set shls.status = REVOKED (files remain encrypted in S3, inaccessible via protocol)

Expiration:
  Checked at access time (shls.expirationTime vs. current time)
  S3 lifecycle rule transitions expired files to Glacier

Access Logging:
  Every manifest/direct access -> DynamoDB PutItem (fire-and-forget, 10-year retention)
```
