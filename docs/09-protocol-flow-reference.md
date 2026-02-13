# End-to-End Protocol Flow Reference

## Complete Sequence Diagrams

### Flow 1: Standard SHL (No Passcode, No U-Flag)

```
Patient App          SHL Server           HealthLake         MongoDB
    │                    │                    │                 │
    │ POST /api/shl      │                    │                 │
    │ {patientId,        │                    │                 │
    │  categories:[      │                    │                 │
    │   CONDITIONS,      │                    │                 │
    │   MEDICATIONS]}    │                    │                 │
    │ ──────────────────>│                    │                 │
    │                    │                    │                 │
    │                    │ Generate:          │                 │
    │                    │  key (32 bytes)    │                 │
    │                    │  manifestId        │                 │
    │                    │  mgmtToken (UUID)  │                 │
    │                    │                    │                 │
    │                    │ Save ShlDocument───────────────────> │
    │                    │                    │                 │
    │                    │ GET /Condition?    │                 │
    │                    │  patient=123 ─────>│                 │
    │                    │                    │                 │
    │                    │ <── Bundle ────────│                 │
    │                    │                    │                 │
    │                    │ GET /MedicationReq │                 │
    │                    │  ?patient=123 ────>│                 │
    │                    │                    │                 │
    │                    │ <── Bundle ────────│                 │
    │                    │                    │                 │
    │                    │ JWE encrypt each   │                 │
    │                    │  bundle            │                 │
    │                    │                    │                 │
    │                    │ Save ShlFileDocuments ─────────────> │
    │                    │                    │                 │
    │                    │ Encode shlink:/ URI│                 │
    │                    │                    │                 │
    │ <── 201 Created ──│                    │                 │
    │ {shlUri,           │                    │                 │
    │  managementToken,  │                    │                 │
    │  qrCodeDataUri}    │                    │                 │
    │                    │                    │                 │
```

### Flow 2: Provider Consumes SHL

```
Provider EHR         SHL Server                              MongoDB
    │                    │                                      │
    │ Scan QR / paste    │                                      │
    │ shlink:/...        │                                      │
    │                    │                                      │
    │ Decode base64url   │                                      │
    │ -> {url, key, ...} │                                      │
    │                    │                                      │
    │ POST {url}         │                                      │
    │ {recipient:        │                                      │
    │  "Dr. Smith"}      │                                      │
    │ ──────────────────>│                                      │
    │                    │ findByManifestId ────────────────────>│
    │                    │ <── ShlDocument ─────────────────────│
    │                    │                                      │
    │                    │ Validate: not revoked, not expired   │
    │                    │                                      │
    │                    │ findByShlId ─────────────────────────>│
    │                    │ <── ShlFileDocuments ────────────────│
    │                    │                                      │
    │                    │ For each file:                       │
    │                    │   if size <= embeddedLengthMax:      │
    │                    │     embed JWE inline                 │
    │                    │   else:                              │
    │                    │     generate signed location URL     │
    │                    │                                      │
    │ <── 200 OK ───────│                                      │
    │ {status:"finalized"│                                      │
    │  files:[           │                                      │
    │   {contentType,    │                                      │
    │    location:       │                                      │
    │    "https://..."}  │                                      │
    │  ]}                │                                      │
    │                    │                                      │
    │ GET {location}     │                                      │
    │ ──────────────────>│                                      │
    │                    │ Verify HMAC + expiry                 │
    │                    │ findById(fileId) ────────────────────>│
    │                    │ <── ShlFileDocument ─────────────────│
    │ <── application/   │                                      │
    │     jose (JWE) ───│                                      │
    │                    │                                      │
    │ Decrypt JWE with   │                                      │
    │ key from shlink:/  │                                      │
    │ -> FHIR Bundle     │                                      │
    │                    │                                      │
    │ Display patient    │                                      │
    │ records in EHR     │                                      │
```

### Flow 3: Passcode-Protected SHL

```
Provider EHR         SHL Server                              MongoDB
    │                    │                                      │
    │ POST {url}         │                                      │
    │ {recipient:"...",  │                                      │
    │  passcode:"wrong"} │                                      │
    │ ──────────────────>│                                      │
    │                    │ findByManifestId ────────────────────>│
    │                    │ <── ShlDocument (P flag) ────────────│
    │                    │                                      │
    │                    │ BCrypt verify: MISMATCH              │
    │                    │                                      │
    │                    │ atomic $inc(-1) ─────────────────────>│
    │                    │ <── {remainingAttempts: 4} ──────────│
    │                    │                                      │
    │ <── 401 ──────────│                                      │
    │ {remainingAttempts: │                                      │
    │  4}                │                                      │
    │                    │                                      │
    │ POST {url}         │                                      │
    │ {recipient:"...",  │                                      │
    │  passcode:"1234"}  │  (correct this time)                │
    │ ──────────────────>│                                      │
    │                    │ BCrypt verify: MATCH                 │
    │                    │                                      │
    │ <── 200 OK ───────│                                      │
    │ {status, files}    │                                      │
```

### Flow 4: U-Flag Direct Access

```
Provider EHR         SHL Server                              MongoDB
    │                    │                                      │
    │ Decode shlink:/    │                                      │
    │ -> {url, key,      │                                      │
    │     flag: "U"}     │                                      │
    │                    │                                      │
    │ GET {url}?         │                                      │
    │  recipient=Dr.+S   │                                      │
    │ ──────────────────>│                                      │
    │                    │ findByManifestId ────────────────────>│
    │                    │ <── ShlDocument ─────────────────────│
    │                    │                                      │
    │                    │ Validate status                      │
    │                    │                                      │
    │                    │ findByShlId().next() ────────────────>│
    │                    │ <── Single ShlFileDocument ──────────│
    │                    │                                      │
    │ <── application/   │                                      │
    │     jose (JWE) ───│                                      │
    │                    │                                      │
    │ Decrypt -> FHIR    │                                      │
```

### Flow 5: L-Flag Data Refresh

```
Patient App          SHL Server           HealthLake         MongoDB
    │                    │                    │                 │
    │ POST /manage/      │                    │                 │
    │  {token}/refresh   │                    │                 │
    │ ──────────────────>│                    │                 │
    │                    │ findByMgmtToken ──────────────────> │
    │                    │ <── ShlDocument (L flag) ──────────  │
    │                    │                    │                 │
    │                    │ deleteByShlId ────────────────────> │
    │                    │  (old files)       │                 │
    │                    │                    │                 │
    │                    │ Fetch fresh FHIR──>│                 │
    │                    │ <── Bundles ──────│                 │
    │                    │                    │                 │
    │                    │ JWE encrypt (same  │                 │
    │                    │  key as before)    │                 │
    │                    │                    │                 │
    │                    │ Save new files ──────────────────> │
    │                    │ Update timestamp ─────────────────> │
    │                    │                    │                 │
    │ <── 204 ──────────│                    │                 │
    │                    │                    │                 │
    │ Next time provider │                    │                 │
    │ fetches manifest:  │                    │                 │
    │ status="can-change"│                    │                 │
    │ files=new data     │                    │                 │
```

### Flow 6: SHC Verification

```
Verifier App         SHL Server
    │                    │
    │ Decrypt JWE        │
    │ -> {"verifiable    │
    │     Credential":   │
    │     ["<JWS>"]}     │
    │                    │
    │ Parse JWS header   │
    │ -> {kid: "3Kf..."} │
    │                    │
    │ GET /.well-known/  │
    │  jwks.json         │
    │ ──────────────────>│
    │                    │
    │ <── {"keys":[{     │
    │   kty:"EC",        │
    │   kid:"3Kf...",    │
    │   crv:"P-256",     │
    │   x:"...",         │
    │   y:"..."          │
    │ }]} ──────────────│
    │                    │
    │ Verify ES256 sig   │
    │ with public key    │
    │                    │
    │ Decompress payload │
    │ (DEFLATE)          │
    │                    │
    │ Parse VC JSON      │
    │ -> fhirBundle      │
    │                    │
    │ Verify iss matches │
    │ JWKS source URL    │
    │                    │
    │ Display verified   │
    │ health data        │
```

---

## API Reference Summary

### Protocol Endpoints (Public, @CrossOrigin("*"))

| Method | Path | Request | Response | Auth |
|---|---|---|---|---|
| POST | `/api/shl/manifest/{manifestId}` | `{recipient, passcode?, embeddedLengthMax?}` | `{status, files[]}` | manifestId entropy + optional passcode |
| GET | `/api/shl/file/{signedToken}` | - | `application/jose` body | HMAC-signed URL |
| GET | `/api/shl/direct/{manifestId}?recipient=...` | - | `application/jose` body | manifestId entropy (U-flag only) |
| GET | `/.well-known/jwks.json` | - | `{keys: [...]}` | None |

### Management Endpoints (Internal)

| Method | Path | Request | Response | Auth |
|---|---|---|---|---|
| POST | `/api/shl` | `CreateShlRequest` | `CreateShlResponse` (201) | None (phase 1) |
| GET | `/api/shl/manage/{managementToken}` | - | `ShlStatusResponse` | managementToken |
| DELETE | `/api/shl/manage/{managementToken}` | - | 204 | managementToken |
| POST | `/api/shl/manage/{managementToken}/refresh` | - | 204 | managementToken |
| GET | `/api/shl/manage/{managementToken}/qr` | - | `image/png` | managementToken |

### HealthLake Endpoints (Internal)

| Method | Path | Params | Response |
|---|---|---|---|
| GET | `/api/healthlake/categories` | - | `[{name, resourceType}]` |
| GET | `/api/healthlake/preview` | `patientId, categories, from?, to?` | `Flux<FhirBundleWrapper>` |

---

## Data Structures Quick Reference

### SHL Payload (in shlink:/ URI)
```json
{"url":"...","key":"...","exp":N,"flag":"LP","label":"...","v":1}
```

### Manifest Response
```json
{"status":"finalized|can-change","files":[{"contentType":"...","location":"...","embedded":"...","lastUpdated":"..."}]}
```

### JWE Header
```json
{"alg":"dir","enc":"A256GCM","cty":"application/fhir+json;fhirVersion=4.0.1"}
```

### SHC JWS Header
```json
{"alg":"ES256","zip":"DEF","kid":"base64url-sha256-thumbprint"}
```

### SHC VC Payload (after decompress)
```json
{"iss":"https://...","nbf":N,"vc":{"type":["https://smarthealth.cards#health-card"],"credentialSubject":{"fhirVersion":"4.0.1","fhirBundle":{...}}}}
```

### 401 Passcode Error
```json
{"remainingAttempts":4}
```
