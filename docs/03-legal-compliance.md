# Legal & Compliance Documentation

## Regulatory Framework

SHL2 implements the **HL7 SMART Health Links v1.0.0** specification, a standards-track IG (Implementation Guide) published under HL7 FHIR. The system handles Protected Health Information (PHI) and is designed for compliance with:

- **HIPAA** (Health Insurance Portability and Accountability Act)
- **HITECH** Act
- **21st Century Cures Act** (information blocking provisions)
- **HL7 FHIR R4** data standards
- **SMART Health Cards** specification (verifiable credentials)

---

## What the System Does

SHL2 enables **patient-directed sharing** of health records. A patient selects which categories of their health data to share, and the system:

1. Fetches the selected FHIR resources from AWS HealthLake
2. Encrypts them with a unique AES-256-GCM key
3. Generates a shareable link containing the manifest URL and decryption key
4. Optionally protects the link with a passcode
5. Optionally signs the data as SMART Health Cards (verifiable credentials)
6. The provider who receives the link can decrypt and view the data

The patient retains control: they choose what to share, can set expiration, add passcode protection, and revoke access at any time.

---

## Data Protection Measures

### Encryption

| Layer | Algorithm | Standard | Purpose |
|---|---|---|---|
| Data at rest (files) | AES-256-GCM via JWE | NIST SP 800-38D | FHIR bundles encrypted before storage in MongoDB |
| Key in transit (SHL URI) | Base64url in link | SHL spec | Decryption key embedded in the link itself |
| SHC signing | ES256 (ECDSA P-256) | FIPS 186-4 | Tamper-proof verifiable credentials |
| Passcode storage | BCrypt | OWASP recommended | Passcodes never stored in plaintext |
| File URL integrity | HMAC-SHA256 | RFC 2104 | Prevents URL tampering and enforces expiry |

### Key Management

- **Encryption keys**: 32 bytes (256 bits) generated from `java.security.SecureRandom` (CSPRNG). One unique key per SHL. Never stored separately — only in MongoDB document and in the SHL URI.
- **Signing key**: EC P-256 private key in JWK format. Must be stored in a secrets manager in production. Only the public key is exposed via `/.well-known/jwks.json`.
- **HMAC signing secret**: Configurable via environment variable. Used for file URL signing only.

### Access Control

- **No server-side authentication on management API** (current phase): Management tokens (UUIDs) serve as bearer tokens. This is a deliberate simplification for initial deployment — production should add OAuth2/OIDC.
- **Protocol endpoints**: Public by design (SHL spec). Access controlled by:
  - Knowledge of the manifest URL (256-bit entropy)
  - Knowledge of the encryption key (256-bit entropy)
  - Passcode verification (BCrypt, with attempt limiting)
- **Passcode brute-force protection**: Configurable maximum attempts (default: 5). Atomic decrement prevents race conditions. After exhaustion, the passcode is permanently locked.

---

## Data Flow and PHI Handling

### Where PHI Exists

| Location | PHI Present | Protection | Retention |
|---|---|---|---|
| AWS HealthLake | Yes | AWS encryption at rest, IAM, VPC | Source system — managed by AWS |
| MongoDB `shls` collection | Minimal (patient ID, metadata) | Database encryption, access control | Until SHL deleted |
| MongoDB `shl_files` collection | Yes (JWE-encrypted FHIR bundles) | AES-256-GCM encrypted before storage | Until SHL deleted or refreshed |
| SHL URI | Yes (contains decryption key) | Shared only with patient; patient shares at discretion | Ephemeral (in patient's possession) |
| File location URLs | No (signed pointer only) | HMAC-SHA256, 1-hour expiry | Ephemeral |
| Server memory | Transiently (during encrypt/decrypt) | Garbage collected after request | Request duration only |
| Server logs | Must NOT contain PHI | Log sanitization required | Per log retention policy |

### Data Minimization

- Patient selects only the categories they want to share
- Optional date range filtering limits data scope
- SHC minification strips non-essential fields (narrative text, display strings)
- Encryption keys are per-SHL — compromising one key does not expose other SHLs

---

## Spec Compliance Validation

### HL7 SMART Health Links v1.0.0

| Requirement | Implementation | Validation |
|---|---|---|
| SHL payload: url max 128 chars | URL constructed as baseUrl + path + 43-char manifestId | Unit test: measure encoded URL length |
| SHL payload: key = 32 bytes base64url | `SecureRandom` 32 bytes -> base64url (43 chars) | Unit test: verify key length and entropy |
| SHL payload: flags alphabetically sorted | `Stream.sorted()` before joining | Unit test: verify flag ordering |
| SHL payload: label max 80 chars | `@Size(max=80)` validation annotation | Request validation at controller |
| Manifest status: finalized/can-change | L-flag -> "can-change", else "finalized" | Integration test |
| 401 response: `{"remainingAttempts": N}` | Exact format in GlobalExceptionHandler | Integration test |
| File URLs: max 1 hour lifetime | Configurable `fileUrlExpirySeconds`, default 3600 | HMAC expiry verification |
| JWE: alg=dir, enc=A256GCM, cty header | Nimbus JOSE+JWT library, explicit header | Unit test: parse JWE header |
| U+P mutual exclusion | Validated in ShlCreationService | Unit test |
| U-flag: single encrypted file | All bundles merged for U-flag | Unit test: verify single file |
| Passcode: atomic attempt tracking | MongoDB `$inc` with `findAndModify` | Concurrency test |

### SMART Health Cards

| Requirement | Implementation | Validation |
|---|---|---|
| JWS header: alg=ES256, zip=DEF, kid | Nimbus JOSE+JWT, kid from RFC 7638 thumbprint | Unit test: parse JWS header |
| VC type: `#health-card` only | No deprecated types included | Code review |
| FHIR minification | Strip id, meta (keep security), text, CodeableConcept.text, Coding.display | Unit test with sample bundle |
| Reference rewriting | `Reference.reference` -> `resource:N` | Unit test |
| DEFLATE: raw RFC 1951 | `Deflater(BEST_COMPRESSION, true)` | Decompress test |
| JWKS endpoint: public key only | `toPublicJWK()` strips `d` parameter | Unit test: verify no private key |

---

## Audit Trail

### What is Logged

- SHL creation events (timestamp, patient ID, categories selected, flags)
- Manifest access events (timestamp, manifestId, recipient, success/failure)
- Passcode failure events (timestamp, manifestId, remaining attempts)
- SHL revocation events (timestamp, managementToken)
- SHL refresh events (timestamp, managementToken)
- HealthLake fetch events (timestamp, patient ID, resource types, success/failure)

### What Must NOT be Logged

- Encryption keys
- Decryption keys
- Passcodes (plaintext)
- FHIR resource content (PHI)
- Full SHL URIs (contain the decryption key)
- File content (encrypted or decrypted)

---

## Patient Rights

| Right | Mechanism |
|---|---|
| Right to share | Patient selects categories and creates SHL |
| Right to limit | Category selection, date range filtering |
| Right to revoke | DELETE /api/shl/manage/{token} |
| Right to know | GET /api/shl/manage/{token} shows status and file count |
| Right to time-limit | Expiration time set at creation |
| Right to protect | Passcode protection with attempt limiting |

---

## Risk Assessment

### Threat Model

| Threat | Mitigation | Residual Risk |
|---|---|---|
| Link interception | 256-bit key in URI, optional passcode, HTTPS | Low (if HTTPS enforced) |
| Brute-force passcode | BCrypt + attempt limiting (default 5) | Low |
| Database compromise | FHIR data stored JWE-encrypted; key not in DB | Low (attacker gets ciphertext only) |
| Key in SHL URI | Patient controls distribution; key not stored server-side separately | Medium (depends on patient behavior) |
| Signing key compromise | Key rotation via JWKS endpoint, store in HSM/secrets manager | Low (if properly managed) |
| HealthLake access | AWS IAM, SigV4, VPC networking | Low (standard AWS security) |
| Replay of file URLs | 1-hour expiry, HMAC verification | Low |

### Compliance Gaps (Current Phase)

| Gap | Risk | Remediation Plan |
|---|---|---|
| No auth on management API | Medium | Add OAuth2/OIDC in next phase |
| No audit database | Medium | Add structured audit logging to separate store |
| No BAA with MongoDB provider | High (if cloud-hosted) | Execute BAA before production deployment |
| Development signing key in repo | Low (dev only) | Production key in secrets manager |

---

## Certification Path

1. **SOC 2 Type II**: Implement audit logging, access controls, encryption validation
2. **HITRUST CSF**: Map controls to HITRUST framework requirements
3. **ONC Certification**: SMART Health Cards issuance per ONC Health IT standards
4. **HL7 Conformance**: Validate against HL7 SMART Health Links test suite when available
