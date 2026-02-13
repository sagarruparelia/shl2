# Security Architecture

## Security Model

SHL2 implements a **zero-knowledge architecture** for stored health data: the server stores encrypted FHIR data (JWE) but the decryption key is only embedded in the SHL URI held by the patient. Even a full database compromise yields only ciphertext.

---

## Encryption Layers

### Layer 1: Data Encryption (JWE)

Every FHIR bundle and Smart Health Card is encrypted before storage.

```
Plaintext (FHIR JSON) -> JWE (AES-256-GCM) -> MongoDB

JWE Header: {
  "alg": "dir",           // Direct key agreement (symmetric)
  "enc": "A256GCM",       // AES-256 in GCM mode (authenticated encryption)
  "cty": "application/fhir+json;fhirVersion=4.0.1"
}
```

**Properties:**
- **Confidentiality**: AES-256 (NIST approved, 256-bit key)
- **Integrity**: GCM mode provides authenticated encryption (built-in AEAD)
- **Uniqueness**: Each JWE operation generates a unique IV/nonce (Nimbus library handles this)
- **Key per SHL**: Each SHL has its own 256-bit key, never shared between SHLs

### Layer 2: Key Distribution (SHL URI)

The decryption key is embedded in the SHL URI:

```
shlink:/eyJ1cmwiOi...   <- Contains base64url-encoded JSON with "key" field
```

The key is **never stored separately on the server**. It exists in:
1. MongoDB `shls.encryptionKeyBase64` — needed for re-encryption on refresh (L-flag)
2. The SHL URI — given to the patient

**Trade-off**: Storing the key in MongoDB is necessary for L-flag refresh functionality. Without L-flag, the key could theoretically be discarded after initial encryption, but this would prevent data refresh.

### Layer 3: Transport Security (TLS)

All external communication must use TLS 1.2+ (TLS 1.3 preferred):
- Client -> SHL Server: HTTPS
- SHL Server -> MongoDB: TLS (connection string parameter)
- SHL Server -> HealthLake: HTTPS (AWS enforced)

### Layer 4: Signing (JWS for SHCs)

Smart Health Cards use ES256 digital signatures:

```
JWS Header: {
  "alg": "ES256",     // ECDSA with P-256 and SHA-256
  "zip": "DEF",       // DEFLATE compressed payload
  "kid": "3Kfdg-..."  // Key ID (SHA-256 thumbprint per RFC 7638)
}
```

The signing key is an EC P-256 key pair. Only the public key is published at `/.well-known/jwks.json`.

---

## Authentication & Authorization

### Current State (Phase 1)

| Endpoint Type | Auth | Access Control |
|---|---|---|
| Protocol endpoints (manifest, file, direct) | None (public by spec) | Knowledge of manifestId (256-bit entropy) + optional passcode |
| Management endpoints (create, status, revoke) | None | Knowledge of managementToken (UUID) |
| JWKS endpoint | None (public by spec) | N/A |
| HealthLake preview | None | N/A |

### Security Through Entropy

Even without authentication, the protocol is secured by:

| Secret | Entropy | Brute-Force Difficulty |
|---|---|---|
| ManifestId | 256 bits (32 random bytes) | 2^256 attempts (~10^77) |
| Encryption key | 256 bits (32 random bytes) | 2^256 attempts |
| ManagementToken | 122 bits (UUID v4) | 2^122 attempts (~5 x 10^36) |
| Combined (manifestId + key) | 512 bits | Computationally infeasible |

### Planned (Phase 2)

- OAuth2/OIDC for management endpoints
- Patient identity binding to management tokens
- Rate limiting on protocol endpoints
- API key for HealthLake preview endpoints

---

## Passcode Protection

### Hashing

```
Patient passcode -> BCrypt (cost factor 10) -> MongoDB
```

BCrypt properties:
- Incorporates salt (per-hash, random)
- Cost factor makes brute-force expensive (~100ms per attempt on modern hardware)
- Output: `$2a$10$...` (60 chars)

### Attempt Limiting

```
Initial: passcodeFailuresRemaining = 5 (configurable)

On wrong passcode:
  MongoDB atomic: db.shls.findAndModify(
    {manifestId: "...", passcodeFailuresRemaining: {$gt: 0}},
    {$inc: {passcodeFailuresRemaining: -1}},
    {returnNew: true}
  )

On exhaustion (remaining = 0):
  HTTP 401 {"remainingAttempts": 0}
  Link is permanently locked (no recovery without DB intervention)
```

**Concurrency safety**: The `$inc` operation is atomic in MongoDB, preventing race conditions where parallel requests could bypass the limit.

### BCrypt Timing

BCrypt verification runs on `Schedulers.boundedElastic()` to avoid blocking the Netty event loop. This is critical for WebFlux performance.

---

## File URL Security

Manifest responses include `location` URLs for file download. These are protected by HMAC-signed tokens:

```
URL format: /api/shl/file/{fileId}.{expiryEpoch}.{hmac}

HMAC = HMAC-SHA256(
  key:  SHL_SIGNING_SECRET,
  data: "{fileId}.{expiryEpoch}"
)
```

**Protections:**
- **Expiry**: URLs expire after `fileUrlExpirySeconds` (default 3600 = 1 hour, per SHL spec maximum)
- **Integrity**: HMAC prevents tampering with fileId or expiry
- **Constant-time comparison**: `MessageDigest.isEqual()` prevents timing attacks

---

## Signing Key Security

### Key Storage

| Environment | Storage | Access |
|---|---|---|
| Development | `src/main/resources/keys/shc-signing.jwk` | Bundled in JAR |
| Production | AWS Secrets Manager / K8s Secret | Mounted as file, read at startup |

### Key Format

EC P-256 private key in JWK format:

```json
{
  "kty": "EC",
  "crv": "P-256",
  "x": "...",    // Public key x-coordinate
  "y": "...",    // Public key y-coordinate
  "d": "...",    // Private key (SENSITIVE)
  "use": "sig",
  "alg": "ES256"
}
```

### Public Key Exposure

Only the public key (without `d`) is exposed at `/.well-known/jwks.json`:

```json
{
  "keys": [{
    "kty": "EC",
    "kid": "3Kfdg-XwP-7g...",
    "use": "sig",
    "alg": "ES256",
    "crv": "P-256",
    "x": "...",
    "y": "..."
  }]
}
```

### Key Rotation Procedure

1. Generate new EC P-256 key pair
2. Deploy with both old and new keys in JWKS response
3. Switch `JwsService` to sign with the new key
4. After all SHCs signed with old key expire, remove old public key from JWKS

---

## Threat Mitigations

### OWASP Top 10

| Threat | Mitigation |
|---|---|
| **A01: Broken Access Control** | Manifest access requires 256-bit manifestId; management requires UUID token; passcode for P-flag |
| **A02: Cryptographic Failures** | AES-256-GCM (AEAD), ES256 (ECDSA), BCrypt; all via vetted libraries (Nimbus, Spring Security Crypto) |
| **A03: Injection** | No SQL/NoSQL injection: Spring Data MongoDB uses parameterized queries; no user input in queries |
| **A04: Insecure Design** | Zero-knowledge storage; per-SHL keys; atomic passcode tracking |
| **A05: Security Misconfiguration** | Minimal config surface (single properties file); no unnecessary endpoints |
| **A06: Vulnerable Components** | nimbus-jose-jwt 10.0.2 (patches CVE-2025-53864); ZXing 3.5.3 (no known CVEs) |
| **A07: Auth Failures** | BCrypt with attempt limiting; constant-time HMAC comparison |
| **A08: Data Integrity** | JWE provides authenticated encryption; JWS provides tamper detection |
| **A09: Logging Failures** | Structured logging of security events; PHI excluded from logs |
| **A10: SSRF** | HealthLake URL constructed from config only (not user input); Binary URLs validated against pattern |

### Additional Threats

| Threat | Mitigation |
|---|---|
| **Database compromise** | All FHIR data JWE-encrypted; key in SHL URI, not exposed separately |
| **Network interception** | TLS 1.2+ required; encryption key in SHL fragment (not in server logs) |
| **Replay attack on file URLs** | 1-hour HMAC-signed expiry |
| **Timing attack on passcode** | BCrypt is constant-time by design; HMAC uses `MessageDigest.isEqual()` |
| **Denial of service** | WebFlux non-blocking model handles high concurrency; BCrypt on bounded scheduler prevents thread starvation |
| **QR code shoulder surfing** | Optional passcode protection; patient controls physical QR distribution |

---

## Dependency Security

| Dependency | Version | CVE Status | Notes |
|---|---|---|---|
| nimbus-jose-jwt | 10.0.2 | Patched (CVE-2025-53864) | JWE/JWS library |
| ZXing | 3.5.3 | No known CVEs | QR code generation |
| AWS SDK auth | 2.31.1 | Current | SigV4 signing |
| Spring Security Crypto | (managed by Boot) | Current | BCrypt only |
| Spring Boot | 4.0.2 | Current | Framework |
| MongoDB Driver | (managed by Boot) | Current | Database driver |

Monitor via `./mvnw dependency:tree` and vulnerability scanners (Snyk, OWASP Dependency-Check).
