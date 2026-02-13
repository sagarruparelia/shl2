# Internal Development Guide

## Project Overview

SHL2 is a SMART Health Links server that enables patients to share selected FHIR health data from AWS HealthLake with healthcare providers. Built on Spring Boot 4.0.2 (WebFlux), MongoDB Reactive, and Java 25.

## Architecture

```
Patient App  -->  SHL Server  <-->  AWS HealthLake (FHIR R4)
                      |
                      v
             MongoDB (metadata + encrypted files)
                      |
Provider EHR  <--  SHL Protocol (manifest + file download)
```

### Design Principles

- **Config over code**: Single `ShlProperties` class binds all config. No custom `@Configuration` classes.
- **Reactive end-to-end**: All services return `Mono<>` or `Flux<>`. Blocking operations (BCrypt, QR generation) run on `Schedulers.boundedElastic()`.
- **Spec compliance**: HL7 SMART Health Links v1.0.0 and SMART Health Cards specifications.

## Package Structure

```
com.chanakya.shl2/
  config/           ShlProperties (single @ConfigurationProperties)
  model/
    enums/          ShlFlag, ShlStatus, FhirCategory
    document/       ShlDocument, ShlFileDocument (MongoDB)
    dto/
      request/      CreateShlRequest, ManifestRequest
      response/     CreateShlResponse, ManifestResponse, ManifestFileEntry,
                    ShlStatusResponse, ErrorResponse
    fhir/           FhirBundleWrapper
  repository/       ShlRepository, ShlFileRepository, custom impl
  util/             EntropyUtil, Base64UrlUtil
  crypto/           KeyGenerationService, JweService, JwsService, ShlPayloadEncoder
  service/          FileAccessService, PasscodeService, HealthLakeService,
                    ManifestService, ShlCreationService, QrCodeService,
                    SmartHealthCardService
  controller/       ShlProtocolController, ShlManagementController,
                    WellKnownController, HealthLakeController
  exception/        Custom exceptions + GlobalExceptionHandler
```

## Key Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `nimbus-jose-jwt` | 10.0.2 | JWE encryption (A256GCM), JWS signing (ES256) |
| `zxing-core` + `javase` | 3.5.3 | QR code generation |
| `aws-sdk-auth` | BOM 2.31.1 | SigV4 signing for HealthLake |
| `spring-security-crypto` | (managed) | BCrypt password hashing |
| `spring-boot-starter-validation` | (managed) | Request validation |
| `spring-boot-starter-json` | (managed) | Jackson 3.x (tools.jackson.databind) |

## Important: Jackson 3.x in Spring Boot 4

Spring Boot 4 uses Jackson 3.x. The package changed:

```java
// OLD (Jackson 2.x) - DO NOT USE
import com.fasterxml.jackson.databind.ObjectMapper;

// NEW (Jackson 3.x) - USE THIS
import tools.jackson.databind.ObjectMapper;
```

Other API changes:
- `ObjectNode.fieldNames()` -> `ObjectNode.propertyNames()` (returns `Collection<String>`)
- `JsonNode.deepCopy()` returns `JsonNode` (cast explicitly to `ObjectNode` when needed)
- Annotations remain in `com.fasterxml.jackson.annotation`

## Configuration

All configuration lives in `application.yml` with env var overrides:

| Property | Env Var | Default | Description |
|---|---|---|---|
| `shl.base-url` | `SHL_BASE_URL` | `http://localhost:8080` | Public base URL for link generation |
| `shl.file-url-expiry-seconds` | - | `3600` | Signed file URL lifetime (max 1hr per spec) |
| `shl.signing-secret` | `SHL_SIGNING_SECRET` | dev default | HMAC secret for signed file URLs |
| `shl.default-passcode-attempts` | - | `5` | Max wrong passcode attempts before lockout |
| `shl.shc.issuer-url` | `SHC_ISSUER_URL` | `https://shl.example.com` | SHC issuer (must be HTTPS in prod) |
| `shl.shc.signing-key-path` | `SHC_SIGNING_KEY_PATH` | `classpath:keys/shc-signing.jwk` | EC P-256 private key in JWK format |
| `shl.aws.region` | `AWS_REGION` | `us-east-1` | AWS region |
| `shl.aws.healthlake-datastore-id` | `AWS_HEALTHLAKE_DATASTORE_ID` | - | HealthLake datastore ID |
| `spring.data.mongodb.uri` | `MONGODB_URI` | `mongodb://localhost:27017/shl2` | MongoDB connection |

## Data Flow

### SHL Creation (POST /api/shl)

```
1. Generate encryption key (32 bytes AES-256)
2. Generate manifestId (32 bytes random -> base64url = 43 chars)
3. Generate managementToken (UUID)
4. Hash passcode with BCrypt (if provided)
5. Validate U+P mutual exclusion
6. Save ShlDocument to MongoDB
7. Fetch FHIR data from HealthLake per category
8. For U-flag: merge all bundles into single file
   For non-U: one file per category bundle
9. Encrypt each file with JWE (alg:dir, enc:A256GCM, cty:<type>)
10. Save ShlFileDocuments to MongoDB
11. If includeHealthCards: generate SHCs, encrypt, save
12. Encode SHL payload -> base64url -> "shlink:/" URI
13. Optionally generate QR code
14. Return {shlUri, managementToken, qrCodeDataUri?, ...}
```

### Manifest Resolution (POST /api/shl/manifest/{manifestId})

```
1. Lookup ShlDocument by manifestId
2. Validate status (not revoked, not expired) -> 404
3. Verify passcode if P-flag -> 401 {"remainingAttempts": N}
4. Build file list:
   - If content fits embeddedLengthMax: embed JWE inline
   - Otherwise: generate HMAC-signed location URL (1hr expiry)
5. Return {status: "finalized"|"can-change", files: [...]}
```

## Crypto Operations

| Operation | Algorithm | Library |
|---|---|---|
| Encryption key | 32 bytes SecureRandom | JDK |
| JWE encrypt/decrypt | `alg:dir`, `enc:A256GCM` | Nimbus JOSE+JWT |
| JWS sign (SHC) | ES256 (P-256 + SHA-256) | Nimbus JOSE+JWT |
| JWK Thumbprint (kid) | SHA-256 per RFC 7638 | Nimbus JOSE+JWT |
| Passcode hash | BCrypt | spring-security-crypto |
| Signed URLs | HMAC-SHA256 | JDK |
| ManifestId | 32 bytes SecureRandom -> base64url | JDK |

## MongoDB Collections

### `shls`
Indexes: `manifestId` (unique), `managementToken` (unique)

### `shl_files`
Index: `shlId`

Auto-index creation enabled via `spring.data.mongodb.auto-index-creation: true`.

## Running Locally

```bash
# Prerequisites: MongoDB running on localhost:27017

# Build
./mvnw clean compile

# Run
./mvnw spring-boot:run

# With env overrides
SHL_BASE_URL=https://shl.example.com \
AWS_HEALTHLAKE_DATASTORE_ID=your-id \
./mvnw spring-boot:run
```

## Signing Key Management

The EC P-256 signing key at `src/main/resources/keys/shc-signing.jwk` is for **development only**.

To generate a production key:

```java
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

var key = new ECKeyGenerator(Curve.P_256)
    .keyUse(KeyUse.SIGNATURE)
    .algorithm(JWSAlgorithm.ES256)
    .generate();

System.out.println(key.toJSONString());
```

Store the production key in a secrets manager and reference via `SHC_SIGNING_KEY_PATH=file:/path/to/key.jwk`.

## Testing

### Crypto Roundtrip
```java
String key = keyGenService.generateAes256Key();
String encrypted = jweService.encrypt("test", key, "text/plain");
String decrypted = jweService.decrypt(encrypted, key);
assert "test".equals(decrypted);
```

### End-to-End with curl
```bash
# Create SHL
curl -X POST http://localhost:8080/api/shl \
  -H 'Content-Type: application/json' \
  -d '{"patientId":"patient-123","categories":["CONDITIONS"]}'

# Decode shlink:/ URI, extract url and key
# POST to manifest url
curl -X POST {url} \
  -H 'Content-Type: application/json' \
  -d '{"recipient":"Dr. Smith"}'

# GET file from location URL
curl {location}  # Returns application/jose

# Decrypt JWE with the key -> FHIR Bundle JSON
```
