# API Consumer Guide — Generating and Using SMART Health Links

## Overview

This guide explains how to integrate with the SHL2 API to create SMART Health Links for patients and how providers consume those links in their EHR systems.

## Base URL

```
Production:  https://shl.yourdomain.com
Development: http://localhost:8080
```

---

## Part 1: Creating a SMART Health Link (Patient/App Side)

### Step 1: List Available FHIR Categories

```http
GET /api/healthlake/categories
```

**Response:**
```json
[
  {"name": "PATIENT_DEMOGRAPHICS", "resourceType": "Patient"},
  {"name": "CONDITIONS", "resourceType": "Condition"},
  {"name": "MEDICATIONS", "resourceType": "MedicationRequest"},
  {"name": "LAB_RESULTS", "resourceType": "Observation"},
  {"name": "VITAL_SIGNS", "resourceType": "Observation"},
  {"name": "IMMUNIZATIONS", "resourceType": "Immunization"},
  {"name": "ALLERGIES", "resourceType": "AllergyIntolerance"},
  {"name": "PROCEDURES", "resourceType": "Procedure"},
  {"name": "DIAGNOSTIC_REPORTS", "resourceType": "DiagnosticReport"},
  {"name": "ENCOUNTERS", "resourceType": "Encounter"},
  {"name": "CLINICAL_DOCUMENTS", "resourceType": "DocumentReference"}
]
```

### Step 2: Preview Data (Optional)

```http
GET /api/healthlake/preview?patientId=patient-123&categories=CONDITIONS,MEDICATIONS
```

Returns FHIR Bundles so the patient can review what will be shared.

### Step 3: Create the SHL

```http
POST /api/shl
Content-Type: application/json
```

**Request Body:**
```json
{
  "patientId": "patient-123",
  "categories": ["CONDITIONS", "MEDICATIONS", "LAB_RESULTS"],
  "timeframeStart": "2024-01-01T00:00:00Z",
  "timeframeEnd": "2025-12-31T23:59:59Z",
  "label": "Records for Dr. Smith visit",
  "passcode": "1234",
  "flags": ["L"],
  "includeHealthCards": true,
  "generateQrCode": true
}
```

**Fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `patientId` | string | Yes | Patient ID in HealthLake |
| `categories` | string[] | Yes | FHIR categories to include (see list above) |
| `timeframeStart` | ISO 8601 | No | Only include data after this date |
| `timeframeEnd` | ISO 8601 | No | Only include data before this date |
| `label` | string | No | Display label (max 80 chars) |
| `passcode` | string | No | If provided, link requires this passcode. Automatically adds P flag. |
| `flags` | string[] | No | `L` = long-term (data can refresh), `U` = direct file (single encrypted file via GET) |
| `includeHealthCards` | boolean | No | Generate SMART Health Cards (signed, verifiable credentials) |
| `generateQrCode` | boolean | No | Include QR code as data URI in response |

**Flag Rules:**
- `P` is automatically added when `passcode` is provided
- `U` and `P` cannot be combined (direct access links cannot have passcodes)
- `L` allows refreshing the data later via the management API

**Response (201 Created):**
```json
{
  "shlUri": "shlink:/eyJ1cmwiOiJodHRwczovL3NobC5leGFtcGxlLmNvbS9hcGkvc2hsL21hbmlmZXN0L2FiYzEyMyIsImtleSI6InJ4VGdZbE9hS0pQRnRjRWQwcWNjZU44d0VVNHA5NFNxQXdJV1FlNnVYN1EiLCJmbGFnIjoiTFAiLCJsYWJlbCI6IlJlY29yZHMgZm9yIERyLiBTbWl0aCB2aXNpdCIsInYiOjF9",
  "managementToken": "550e8400-e29b-41d4-a716-446655440000",
  "qrCodeDataUri": "data:image/png;base64,iVBORw0KGgo...",
  "expirationTime": "2025-12-31T23:59:59Z",
  "label": "Records for Dr. Smith visit"
}
```

**Important:** Save the `managementToken` — it is the only way to manage (check status, revoke, refresh) this link.

### Step 4: Share the Link

The `shlUri` (starting with `shlink:/`) can be:
- Shared directly as a link
- Embedded in a QR code (use `qrCodeDataUri` from response)
- Prefixed with a viewer URL: `https://viewer.example.org#shlink:/...`

### Management Operations

#### Check Status
```http
GET /api/shl/manage/{managementToken}
```

**Response:**
```json
{
  "manifestId": "abc123...",
  "label": "Records for Dr. Smith visit",
  "status": "ACTIVE",
  "flags": ["L", "P"],
  "expirationTime": "2025-12-31T23:59:59Z",
  "fileCount": 4,
  "createdAt": "2025-01-15T10:30:00Z"
}
```

#### Revoke Link
```http
DELETE /api/shl/manage/{managementToken}
```
Returns `204 No Content`. The link becomes permanently inaccessible.

#### Refresh Data (L-flag only)
```http
POST /api/shl/manage/{managementToken}/refresh
```
Returns `204 No Content`. Re-fetches all FHIR data from HealthLake, re-encrypts, and replaces existing files.

#### Download QR Code
```http
GET /api/shl/manage/{managementToken}/qr
Accept: image/png
```
Returns a PNG image of the QR code.

---

## Part 2: Consuming a SMART Health Link (Provider/EHR Side)

This is the SHL protocol flow that an EHR or SMART app follows when a provider receives a link.

### Step 1: Decode the SHL URI

```
shlink:/eyJ1cmwiOiJodHRwczovL...
```

1. Strip the `shlink:/` prefix
2. Base64url-decode the remaining string
3. Parse as JSON:

```json
{
  "url": "https://shl.example.com/api/shl/manifest/abc123...",
  "key": "rxTgYlOaKJPFtcEd0qcceN8wEU4p94SqAwIWQe6uX7Q",
  "flag": "LP",
  "label": "Records for Dr. Smith visit",
  "exp": 1735689599,
  "v": 1
}
```

### Step 2a: Fetch Manifest (Standard Links)

If the link does NOT have the `U` flag:

```http
POST {url}
Content-Type: application/json

{
  "recipient": "Dr. Smith, City Hospital",
  "passcode": "1234",
  "embeddedLengthMax": 10000000
}
```

**Response:**
```json
{
  "status": "finalized",
  "files": [
    {
      "contentType": "application/fhir+json;fhirVersion=4.0.1",
      "location": "https://shl2-files.s3.amazonaws.com/shl-files/abc123/.../...?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=...",
      "lastUpdated": "2025-01-15T10:30:00Z"
    },
    {
      "contentType": "application/smart-health-card",
      "embedded": "eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIiwiY3R5IjoiYXBwbGljYXRpb24vc21hcnQtaGVhbHRoLWNhcmQifQ...",
      "lastUpdated": "2025-01-15T10:30:00Z"
    }
  ]
}
```

**Status values:**
- `"finalized"` — data will not change
- `"can-change"` — data may update (L-flag); client should re-fetch periodically

### Step 2b: Direct File Access (U-flag Links)

If the link has the `U` flag, issue a GET directly:

```http
GET {url}?recipient=Dr.+Smith
```

Response: `application/jose` body (JWE compact serialization).

### Step 3: Retrieve Files

For files with a `location` URL (not embedded):

```http
GET {location}
```

The `location` is an **S3 presigned URL** — the GET request goes directly to AWS S3 (not through the SHL server).

Response Content-Type: `application/jose`
Response Body: JWE compact serialization string

**Important:** Presigned URLs expire within 1 hour. Fetch them promptly.

### Step 4: Decrypt Content

Each file (whether from `location` or `embedded`) is a JWE compact string encrypted with `alg:dir`, `enc:A256GCM`.

Decrypt using the `key` from the SHL payload:

```javascript
// Pseudocode
const keyBytes = base64urlDecode(shlPayload.key);  // 32 bytes
const plaintext = jweDecrypt(encryptedContent, keyBytes);
// plaintext is JSON:
// - For application/fhir+json: a FHIR Bundle
// - For application/smart-health-card: {"verifiableCredential": ["<JWS>"]}
```

### Step 5: Process FHIR Data

The decrypted FHIR Bundle contains the selected health records:

```json
{
  "resourceType": "Bundle",
  "type": "searchset",
  "total": 3,
  "entry": [
    {
      "resource": {
        "resourceType": "Condition",
        "code": { "coding": [{ "system": "http://snomed.info/sct", "code": "44054006" }] },
        "subject": { "reference": "Patient/patient-123" }
      }
    }
  ]
}
```

### Step 6: Verify Health Cards (Optional)

For `application/smart-health-card` content:

1. Decrypt JWE to get `{"verifiableCredential": ["<JWS>"]}`
2. Parse the JWS (3 base64url parts separated by dots)
3. Fetch the issuer's public key: `GET {iss}/.well-known/jwks.json`
4. Verify the ES256 signature
5. Decompress the payload (DEFLATE, raw RFC 1951)
6. Parse the VC JSON containing the FHIR Bundle

### Passcode Errors

If the link requires a passcode and you provide the wrong one:

```http
HTTP/1.1 401 Unauthorized

{"remainingAttempts": 4}
```

When `remainingAttempts` reaches 0, the link is permanently locked.

---

## Error Responses

| HTTP Status | Scenario | Body |
|---|---|---|
| 201 | SHL created | `CreateShlResponse` |
| 204 | Revoke/refresh successful | (empty) |
| 400 | Invalid request (e.g., U+P flags) | `{"error": "bad_request", "message": "..."}` |
| 401 | Wrong/missing passcode | `{"remainingAttempts": N}` |
| 404 | SHL not found, expired, or revoked | `{"error": "not_found\|expired\|revoked", "message": "..."}` |
| 502 | HealthLake upstream error | `{"error": "healthlake_error", "message": "..."}` |

---

## Code Examples

### Python — Create and Consume SHL

```python
import requests
import base64
import json

# Create
resp = requests.post("https://shl.example.com/api/shl", json={
    "patientId": "patient-123",
    "categories": ["CONDITIONS", "MEDICATIONS"],
    "label": "My health records"
})
shl = resp.json()
print(f"Share this link: {shl['shlUri']}")
print(f"Management token: {shl['managementToken']}")

# Consume
uri = shl["shlUri"]
payload_b64 = uri.replace("shlink:/", "")
payload = json.loads(base64.urlsafe_b64decode(payload_b64 + "=="))

manifest = requests.post(payload["url"], json={"recipient": "Test Consumer"}).json()
for file in manifest["files"]:
    if file.get("location"):
        jwe = requests.get(file["location"]).text  # S3 presigned URL
        # Decrypt with payload["key"] using JWE library
```

### JavaScript — Decode SHL URI

```javascript
function decodeShlUri(shlUri) {
  const payload = shlUri.replace('shlink:/', '');
  const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
  return JSON.parse(json);
}
```
