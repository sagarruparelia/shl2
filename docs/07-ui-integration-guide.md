# UI Team Integration Guide

## Overview

The SHL2 backend provides APIs that the patient-facing UI consumes to create, manage, and display SMART Health Links. This guide covers every API the UI needs, expected UX flows, QR code handling, and edge cases.

---

## User Flows

### Flow 1: Create a SMART Health Link

```
┌──────────────┐     ┌───────────────┐     ┌──────────────┐     ┌──────────────┐
│ Select Data  │ --> │ Set Options   │ --> │ Confirm &    │ --> │ Share Link   │
│ Categories   │     │ (passcode,    │     │ Create       │     │ (URI, QR,    │
│              │     │  expiry, etc) │     │              │     │  copy)       │
└──────────────┘     └───────────────┘     └──────────────┘     └──────────────┘
```

#### Step 1: Category Selection

**API:** `GET /api/healthlake/categories`

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

**UI Recommendations:**
- Display as a checklist with human-readable labels
- Suggested display names:

| API Name | Display Label |
|---|---|
| `PATIENT_DEMOGRAPHICS` | Personal Information |
| `CONDITIONS` | Diagnoses & Conditions |
| `MEDICATIONS` | Medications |
| `LAB_RESULTS` | Lab Results |
| `VITAL_SIGNS` | Vital Signs |
| `IMMUNIZATIONS` | Immunizations |
| `ALLERGIES` | Allergies |
| `PROCEDURES` | Procedures |
| `DIAGNOSTIC_REPORTS` | Diagnostic Reports |
| `ENCOUNTERS` | Visit History |
| `CLINICAL_DOCUMENTS` | Clinical Documents |

- Allow "Select All" convenience
- At least one category must be selected (server validates `@NotEmpty`)

#### Step 2: Preview Data (Optional)

**API:** `GET /api/healthlake/preview?patientId={id}&categories=CONDITIONS,MEDICATIONS`

Returns FHIR Bundles. The UI can show a count of resources per category:

```
Diagnoses & Conditions: 8 records found
Medications: 12 records found
Lab Results: 45 records found
```

**Note:** This makes live calls to HealthLake. Show a loading spinner. The response can be large — consider not rendering full resources, just counts.

#### Step 3: Configure Options

The UI should collect:

| Field | UI Element | Constraints | Required |
|---|---|---|---|
| `label` | Text input | Max 80 characters | No |
| `passcode` | Password input | Any string | No |
| `timeframeStart` | Date picker | ISO 8601 | No |
| `timeframeEnd` | Date picker | ISO 8601 | No |
| `flags` | Checkboxes | `L` (Long-term), `U` (Direct access) | No |
| `includeHealthCards` | Toggle | boolean | No |
| `generateQrCode` | Toggle (default: true) | boolean | No |

**Validation Rules:**
- If passcode is set, `P` flag is automatically added (server-side)
- `U` flag and passcode cannot both be set — disable passcode input when `U` is checked (and vice versa)
- Show character counter for label (80 char limit)
- `timeframeEnd` must be after `timeframeStart` if both set

**UX copy suggestions:**
- **Long-term (L flag)**: "Allow this link's data to be refreshed with the latest records"
- **Direct access (U flag)**: "Simple link — recipient gets data in one step (no passcode allowed)"
- **Include Health Cards**: "Include signed, verifiable credentials (for pharmacies, schools, border crossings)"

#### Step 4: Create SHL

**API:** `POST /api/shl`

```json
{
  "patientId": "patient-123",
  "categories": ["CONDITIONS", "MEDICATIONS", "LAB_RESULTS"],
  "timeframeStart": "2024-01-01T00:00:00Z",
  "timeframeEnd": "2025-12-31T23:59:59Z",
  "label": "Records for Dr. Smith",
  "passcode": "1234",
  "flags": ["L"],
  "includeHealthCards": true,
  "generateQrCode": true
}
```

**Response (201):**
```json
{
  "shlUri": "shlink:/eyJ1cmwiOiJo...",
  "managementToken": "550e8400-e29b-41d4-a716-446655440000",
  "qrCodeDataUri": "data:image/png;base64,iVBORw0KGgo...",
  "expirationTime": "2025-12-31T23:59:59Z",
  "label": "Records for Dr. Smith"
}
```

**Loading state:** This call can take 2-10 seconds (fetching from HealthLake + encryption). Show a progress indicator with messaging like "Preparing your health records...".

**Error handling:**
- `400`: Show validation errors (e.g., "U and P flags cannot be combined")
- `502`: "Unable to fetch your health records. Please try again later."

#### Step 5: Share the Link

Display the result with sharing options:

1. **Copy Link Button**: Copy `shlUri` to clipboard
2. **QR Code Display**: Render `qrCodeDataUri` as an `<img>` tag
3. **Download QR**: Offer download of the QR code image
4. **Management Token**: Show/copy the management token (with warning to save it)

---

## QR Code Handling

### Displaying QR Codes

The `qrCodeDataUri` from the create response is a **data URI** that can be used directly:

```html
<img src="data:image/png;base64,iVBORw0KGgo..." alt="Health Link QR Code" />
```

Properties:
- Format: PNG
- Size: 400x400 pixels
- Error correction: Level M (15% recovery — per SHL spec)
- Content: The full `shlink:/...` URI

### Downloading QR Code as Image

#### Option A: From create response

Convert the data URI to a downloadable file:

```javascript
function downloadQrCode(dataUri, filename = 'health-link-qr.png') {
  const link = document.createElement('a');
  link.href = dataUri;
  link.download = filename;
  link.click();
}
```

#### Option B: Fetch QR separately

**API:** `GET /api/shl/manage/{managementToken}/qr`

**Response:** `image/png` binary

```javascript
async function fetchQrCode(managementToken) {
  const response = await fetch(`/api/shl/manage/${managementToken}/qr`);
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  // Use url as img src, or trigger download
}
```

### QR Code Sizing

The default QR code is 400x400px. For different display contexts:

| Context | Recommended Size | Notes |
|---|---|---|
| Mobile screen | 250-300px | Fits most phone screens |
| Desktop modal | 400px | Default size from API |
| Print (letter/A4) | 600-800px | Regenerate via QR endpoint or scale CSS |
| Wallet card | 200px | Minimum readable size |

Use CSS `image-rendering: pixelated` when scaling up to keep crisp edges:

```css
.qr-code {
  image-rendering: pixelated;
  image-rendering: -moz-crisp-edges;
  image-rendering: crisp-edges;
}
```

### QR Code Printing

For a print-friendly view, include:
- The QR code image (large, centered)
- The label (human-readable)
- Expiration date (if set)
- Instructions: "Scan this code with your healthcare provider's app"
- If passcode-protected: "Passcode: ____" (blank line for patient to write)

---

## Management Dashboard

### Flow 2: Manage Existing SHLs

The UI should maintain a list of created SHLs (store `managementToken` locally or in user session).

#### List View

For each stored management token, fetch status:

**API:** `GET /api/shl/manage/{managementToken}`

**Response:**
```json
{
  "manifestId": "abc123...",
  "label": "Records for Dr. Smith",
  "status": "ACTIVE",
  "flags": ["L", "P"],
  "expirationTime": "2025-12-31T23:59:59Z",
  "fileCount": 4,
  "createdAt": "2025-01-15T10:30:00Z"
}
```

Display as cards or table rows:

```
┌────────────────────────────────────────────────┐
│ Records for Dr. Smith                          │
│ Status: Active  |  Files: 4  |  Flags: L, P   │
│ Created: Jan 15, 2025  |  Expires: Dec 31, 2025│
│                                                │
│ [View QR]  [Refresh Data]  [Revoke]            │
└────────────────────────────────────────────────┘
```

#### Status Display

| API Status | Display | Color |
|---|---|---|
| `ACTIVE` | Active | Green |
| `REVOKED` | Revoked | Red |
| `EXPIRED` | Expired | Gray |

Check expiration client-side too: if `expirationTime` is in the past and status is `ACTIVE`, show "Expired" in gray.

#### Flag Display

| Flag | Display Label | Icon Suggestion |
|---|---|---|
| `L` | Long-term | Refresh icon |
| `P` | Passcode Protected | Lock icon |
| `U` | Direct Access | Lightning bolt |

#### Actions

**Refresh Data (L-flag only):**

```javascript
await fetch(`/api/shl/manage/${token}/refresh`, { method: 'POST' });
// 204 No Content on success
```

Only show this button when `flags` includes `"L"`. Show confirmation dialog: "This will update the shared data with your latest health records."

**Revoke Link:**

```javascript
await fetch(`/api/shl/manage/${token}`, { method: 'DELETE' });
// 204 No Content on success
```

Show confirmation dialog: "This will permanently disable this health link. Anyone with this link will no longer be able to access your records. This cannot be undone."

**View/Download QR:**

```javascript
const response = await fetch(`/api/shl/manage/${token}/qr`);
// Returns image/png
```

---

## Error States

### API Error Responses

| HTTP Status | When | UI Message |
|---|---|---|
| 400 | Invalid request | Show specific validation error message |
| 404 | SHL not found (manage endpoint) | "This health link was not found. It may have been deleted." |
| 502 | HealthLake unavailable | "We're having trouble connecting to the health records system. Please try again in a few minutes." |

### Network Errors

- Show retry button with "Unable to connect. Check your internet connection."
- For create flow: do NOT auto-retry (could create duplicates)

### Loading States

| Operation | Expected Duration | Loading Message |
|---|---|---|
| Fetch categories | <500ms | Spinner |
| Preview data | 1-3s | "Loading your health records..." |
| Create SHL | 2-10s | "Preparing your secure health link..." |
| Fetch status | <500ms | Spinner |
| Refresh data | 2-10s | "Updating with latest records..." |
| Revoke | <500ms | Spinner |
| Generate QR | <500ms | Spinner |

---

## Local Storage

The UI should persist management tokens so the patient can manage their links across sessions:

```javascript
// Store after creation
const links = JSON.parse(localStorage.getItem('shl_links') || '[]');
links.push({
  managementToken: response.managementToken,
  label: response.label,
  createdAt: new Date().toISOString()
});
localStorage.setItem('shl_links', JSON.stringify(links));
```

**Security note:** Management tokens are sensitive. If the device is shared, consider:
- Session storage instead of local storage
- Encryption with a user PIN
- Clear on logout

---

## Accessibility

- QR code `<img>` tags must have descriptive `alt` text: `alt="QR code for health link: {label}"`
- Provide text alternatives for QR codes (the `shlUri` itself can be copied)
- Use semantic HTML for category checkboxes (`<fieldset>`, `<legend>`)
- Ensure all interactive elements are keyboard accessible
- Color should not be the only indicator of status (use text labels alongside colors)

---

## Content Type Reference

When the UI needs to explain what the patient is sharing:

| Content Type | Explanation for Patient |
|---|---|
| `application/fhir+json;fhirVersion=4.0.1` | "Your health records in a standard medical format" |
| `application/smart-health-card` | "A verified, tamper-proof copy of your health records (like a digital notarized document)" |
