# Observability & Production Support

## Health Checks

Spring Boot Actuator is included. Default endpoints:

```
GET /actuator/health        # Liveness + readiness
GET /actuator/info          # Application info
GET /actuator/metrics       # Micrometer metrics
GET /actuator/prometheus    # Prometheus scrape endpoint (if micrometer-registry-prometheus added)
```

### Health Indicators

| Indicator | What it Checks | Impact if Down |
|---|---|---|
| MongoDB | Connection to MongoDB | All operations fail |
| Disk Space | Available disk | Log writes fail |
| HealthLake | (Custom — should be added) | SHL creation and refresh fail; existing SHLs unaffected |

### Recommended Custom Health Check

Add a health indicator for HealthLake connectivity:

```yaml
# application.yml addition
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  health:
    show-details: when-authorized
```

---

## Key Metrics to Monitor

### Application Metrics

| Metric | Type | Alert Threshold | Description |
|---|---|---|---|
| `http.server.requests` (by status, uri) | Timer | p99 > 2s | Request latency by endpoint |
| `shl.creation.count` | Counter | N/A | SHLs created |
| `shl.creation.duration` | Timer | p99 > 10s | SHL creation time |
| `shl.manifest.requests` | Counter | N/A | Manifest resolutions |
| `shl.passcode.failures` | Counter | >50/min | Possible brute-force attack |
| `shl.passcode.exhaustions` | Counter | >10/min | Lockouts occurring |
| `shl.revocations` | Counter | N/A | SHL revocations |
| `shl.healthlake.errors` | Counter | >5/min | HealthLake upstream failures |
| `shl.healthlake.duration` | Timer | p99 > 5s | HealthLake fetch latency |
| `shl.encryption.duration` | Timer | p99 > 500ms | JWE encryption time |
| `shl.qr.generation.duration` | Timer | p99 > 1s | QR code generation time |

### MongoDB Metrics

| Metric | Alert Threshold | Description |
|---|---|---|
| Connection pool active | >80% of max | Connection pressure |
| Query execution time (p99) | >100ms | Slow queries |
| Replication lag | >10s | Secondary falling behind |
| Disk utilization | >80% | Capacity planning |
| Operations/second | Baseline + 200% | Traffic spike |

### Infrastructure Metrics

| Metric | Alert Threshold | Description |
|---|---|---|
| CPU utilization | >80% sustained 5 min | Scale up needed |
| Memory utilization | >85% | Possible memory leak or large bundles |
| JVM heap usage | >80% of max | GC pressure |
| GC pause time | >500ms | Application stalls |
| Netty event loop pending tasks | >1000 | Event loop congestion |
| Thread pool (boundedElastic) active | >80% of max | Blocking operations saturated |

---

## Logging Strategy

### Log Levels

| Logger | Level | Purpose |
|---|---|---|
| `com.chanakya.shl2` | INFO | Application events |
| `com.chanakya.shl2.service.HealthLakeService` | DEBUG (when investigating) | HealthLake request/response details |
| `com.chanakya.shl2.service.PasscodeService` | WARN (on failures) | Passcode attempts |
| `org.springframework.data.mongodb` | WARN | MongoDB driver issues |
| `reactor.netty` | WARN | Network layer issues |

### Structured Log Format

Use JSON structured logging for production:

```yaml
# application.yml
logging:
  structured:
    format: ecs  # or logstash
```

### What to Log

| Event | Level | Fields |
|---|---|---|
| SHL created | INFO | `event=shl_created, manifestId, patientId, categories, flags, hasPasscode` |
| SHL revoked | INFO | `event=shl_revoked, managementToken` |
| SHL refreshed | INFO | `event=shl_refreshed, managementToken` |
| Manifest accessed | INFO | `event=manifest_accessed, manifestId, recipient, status` |
| File downloaded | INFO | `event=file_downloaded, fileId` |
| Passcode failed | WARN | `event=passcode_failed, manifestId, remainingAttempts` |
| Passcode exhausted | WARN | `event=passcode_exhausted, manifestId` |
| HealthLake error | ERROR | `event=healthlake_error, patientId, category, errorMessage` |
| Encryption error | ERROR | `event=encryption_error, shlId, errorMessage` |
| QR code generated | DEBUG | `event=qr_generated, managementToken` |

### What NOT to Log (PHI/Security)

- Encryption keys or decryption keys
- Passcodes (plaintext or hashed)
- FHIR resource content
- Full SHL URIs
- Encrypted file content
- Patient names, DOBs, SSNs, or other direct identifiers beyond patient ID

---

## Alerting Rules

### Critical (Page On-Call)

| Alert | Condition | Action |
|---|---|---|
| MongoDB Down | Health check failing for >1 min | Investigate MongoDB cluster |
| High Error Rate | HTTP 5xx >5% of requests for 5 min | Check logs, HealthLake status |
| HealthLake Unavailable | All HealthLake requests failing for >2 min | Check AWS status, credentials |
| Application Crash Loop | Pod restarts >3 in 10 min | Check logs, OOM, dependency issues |

### Warning (Notify Team)

| Alert | Condition | Action |
|---|---|---|
| High Latency | p99 latency >3s for 10 min | Scale pods, check MongoDB |
| Brute Force Suspected | >20 passcode failures/min for same manifestId | Consider IP blocking |
| MongoDB Disk >80% | Disk utilization | Plan capacity increase |
| Memory Pressure | JVM heap >85% for 10 min | Investigate, increase memory or tune GC |
| HealthLake Throttling | 429 responses from HealthLake | Request limit increase |

### Info (Dashboard Only)

| Metric | Purpose |
|---|---|
| SHLs created per hour | Usage trends |
| Manifest resolutions per hour | Consumer activity |
| Active SHL count | Capacity planning |
| Average file size | Storage planning |
| QR codes generated per hour | Feature adoption |

---

## Troubleshooting Runbook

### Issue: SHL Creation Returns 502

**Symptoms:** POST /api/shl returns `{"error": "healthlake_error", ...}`

**Steps:**
1. Check HealthLake service health in AWS Console
2. Verify AWS credentials (DefaultCredentialsProvider): `aws sts get-caller-identity`
3. Check datastore ID matches: `AWS_HEALTHLAKE_DATASTORE_ID`
4. Check network connectivity from pods to HealthLake endpoint
5. Look for throttling (429 responses) in application logs
6. Verify patient ID exists in HealthLake: `aws healthlake start-fhir-export-job ...` or FHIR search

### Issue: Manifest Returns 404 for Active SHL

**Symptoms:** POST /api/shl/manifest/{id} returns 404 even though SHL was just created

**Steps:**
1. Verify the manifestId is correct (base64url, 43 chars)
2. Check SHL status in MongoDB: `db.shls.findOne({manifestId: "..."})`
3. Check if expired: compare `expirationTime` with current time
4. Check if revoked: `status` field
5. Check if MongoDB replica lag is causing stale reads

### Issue: Passcode Always Rejected

**Symptoms:** Correct passcode returns 401

**Steps:**
1. Check `passcodeFailuresRemaining` in MongoDB: `db.shls.findOne({manifestId: "..."}, {passcodeFailuresRemaining: 1})`
2. If 0: passcode attempts exhausted, cannot recover
3. Verify BCrypt hash is valid (should start with `$2a$` or `$2b$`)
4. Check if the SHL was created with a different passcode than expected

### Issue: Decryption Fails on Consumer Side

**Symptoms:** Consumer gets JWE but cannot decrypt

**Steps:**
1. Verify the consumer is using the correct key from the SHL payload
2. Verify the key is base64url decoded (not base64)
3. Verify the consumer uses `alg: dir` and `enc: A256GCM`
4. Check the `cty` header in the JWE to ensure correct content type parsing
5. If the SHL was refreshed (L-flag), files were re-encrypted — the key remains the same

### Issue: JWKS Endpoint Returns Error

**Symptoms:** GET /.well-known/jwks.json returns 500

**Steps:**
1. Check the signing key file exists at the configured path
2. Verify the JWK file is valid JSON and contains required EC P-256 fields
3. Check file permissions (readable by application)
4. Check JwsService `@PostConstruct` initialization in logs

---

## Dashboard Layout

### Overview Dashboard
- Request rate (by endpoint)
- Error rate (4xx, 5xx)
- p50/p95/p99 latency
- Active SHL count
- MongoDB connection pool status

### Security Dashboard
- Passcode failure rate (by manifestId)
- Passcode exhaustion events
- Manifest access patterns (by recipient)
- Revocation events

### HealthLake Dashboard
- Request rate to HealthLake
- HealthLake response latency
- Error rate by category
- Throttling events (429s)

### Capacity Dashboard
- MongoDB disk usage
- MongoDB operation rate
- JVM heap usage
- Pod CPU/memory utilization
- ShlFileDocument average size
