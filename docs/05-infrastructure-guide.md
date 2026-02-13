# Infrastructure & Scaling Guide

## System Components

```
                    ┌─────────────────┐
                    │   Load Balancer  │
                    │  (HTTPS/TLS 1.3) │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
        ┌─────┴─────┐ ┌─────┴─────┐ ┌─────┴─────┐
        │  SHL App   │ │  SHL App   │ │  SHL App   │
        │ (Pod 1)    │ │ (Pod 2)    │ │ (Pod N)    │
        └─────┬─────┘ └─────┬─────┘ └─────┬─────┘
              │              │              │
              └──────────────┼──────────────┘
                             │
         ┌───────────┬───────┼───────┬───────────┐
         │           │       │       │           │
   ┌─────┴─────┐ ┌──┴──┐ ┌──┴──┐ ┌──┴──────┐ ┌──┴───────────┐
   │  MongoDB   │ │ S3  │ │Dyn- │ │ Health- │ │  S3 (Glacier) │
   │ (metadata) │ │(JWE │ │amo- │ │  Lake   │ │  (expired     │
   │            │ │files)│ │ DB  │ │(FHIR R4)│ │   files)      │
   └────────────┘ └─────┘ └─────┘ └─────────┘ └──────────────┘
```

---

## Resource Requirements

### Application Pods

| Environment | Instances | CPU | Memory | Notes |
|---|---|---|---|---|
| Development | 1 | 0.5 vCPU | 512 MB | Local MongoDB |
| Staging | 2 | 1 vCPU | 1 GB | Shared MongoDB |
| Production | 3-6 | 2 vCPU | 2 GB | MongoDB Atlas / DocumentDB |

The application is **stateless** — all state lives in MongoDB. Horizontal scaling is straightforward.

### Memory Considerations

- **WebFlux** uses non-blocking I/O with small thread pools (default: CPU cores x 2 Netty event loop threads)
- **BCrypt** verification and **QR code generation** run on `boundedElastic` scheduler (thread pool for blocking operations, default: 10 x CPU cores, max 100K)
- **Large DocumentReference** bundles with embedded PDFs can consume significant memory during encryption and S3 upload. For bundles >10MB, consider streaming encryption (future enhancement).
- JVM heap recommendation: `-Xmx1536m` for production pods with typical workloads.

### MongoDB

| Environment | Configuration | Storage |
|---|---|---|
| Development | Single node, no auth | Local disk |
| Staging | Replica set (3 nodes) | 50 GB SSD |
| Production | Replica set (3 nodes) or Atlas M30+ | 200 GB+ SSD, encrypted at rest |

**Critical MongoDB settings:**
- Enable encryption at rest (contains JWE-encrypted PHI)
- Enable authentication and RBAC
- Network: private subnet, no public access
- Connection pooling: default Spring Data pool (100 connections) is sufficient for most workloads

### AWS HealthLake

HealthLake is a managed service. Key limits:

| Limit | Default | Notes |
|---|---|---|
| Read throughput | 100 RPS per datastore | May need increase for high-volume SHL creation |
| Search results | 100 resources per page | Pagination handled by our service |
| Bundle size | 20 MB | Rare for typical clinical data |

---

## Deployment

### Container Image

```dockerfile
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/shl2-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Environment Variables

| Variable | Required | Description |
|---|---|---|
| `MONGODB_URI` | Yes | MongoDB connection string with credentials |
| `SHL_BASE_URL` | Yes | Public-facing HTTPS URL (e.g., `https://shl.example.com`) |
| `SHC_ISSUER_URL` | Yes | HTTPS URL for SHC issuer (must match `.well-known/jwks.json` host) |
| `SHC_SIGNING_KEY_PATH` | Yes | Path to EC P-256 JWK file (e.g., `file:/secrets/shc-signing.jwk`) |
| `AWS_REGION` | Yes | AWS region for HealthLake, S3, DynamoDB |
| `AWS_HEALTHLAKE_DATASTORE_ID` | Yes | HealthLake datastore ID |
| `SHL_S3_BUCKET` | Yes | S3 bucket for encrypted files (default: `shl2-files`) |
| `SHL_DYNAMO_ACCESS_LOG_TABLE` | Yes | DynamoDB table for access logs (default: `shl2-access-logs`) |
| `AWS_ACCESS_KEY_ID` | Conditional | If not using IAM roles |
| `AWS_SECRET_ACCESS_KEY` | Conditional | If not using IAM roles |

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: shl2
spec:
  replicas: 3
  selector:
    matchLabels:
      app: shl2
  template:
    metadata:
      labels:
        app: shl2
    spec:
      containers:
      - name: shl2
        image: your-registry/shl2:latest
        ports:
        - containerPort: 8080
        resources:
          requests:
            cpu: "1"
            memory: "1Gi"
          limits:
            cpu: "2"
            memory: "2Gi"
        env:
        - name: MONGODB_URI
          valueFrom:
            secretKeyRef:
              name: shl2-secrets
              key: mongodb-uri
        - name: SHL_BASE_URL
          value: "https://shl.example.com"
        - name: SHC_ISSUER_URL
          value: "https://shl.example.com"
        - name: SHC_SIGNING_KEY_PATH
          value: "file:/secrets/shc-signing.jwk"
        - name: AWS_HEALTHLAKE_DATASTORE_ID
          value: "your-datastore-id"
        - name: SHL_S3_BUCKET
          value: "shl2-files"
        - name: SHL_DYNAMO_ACCESS_LOG_TABLE
          value: "shl2-access-logs"
        volumeMounts:
        - name: signing-key
          mountPath: /secrets
          readOnly: true
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 15
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 30
      volumes:
      - name: signing-key
        secret:
          secretName: shl2-signing-key
```

---

## Scaling Strategy

### Horizontal Scaling (Application)

The app is stateless. Scale pods based on:

| Metric | Threshold | Action |
|---|---|---|
| CPU utilization | >70% sustained | Add pod |
| Request latency (p99) | >2s | Add pod |
| Active connections | >500 per pod | Add pod |
| Memory utilization | >80% | Investigate (may indicate large bundles) |

HPA configuration:
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: shl2-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: shl2
  minReplicas: 3
  maxReplicas: 12
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

### Vertical Scaling (MongoDB)

| Signal | Action |
|---|---|
| Write latency >50ms | Increase IOPS or upgrade instance |
| Storage >80% | Expand volume |
| Connection count >80% of limit | Increase pool size or add read replicas |
| Working set > RAM | Upgrade to larger instance |

### HealthLake Throttling

If HealthLake returns 429s:
- The WebClient will see errors in `HealthLakeService`
- Add retry with exponential backoff (future enhancement)
- Request AWS support for throughput increase
- Consider caching frequently-accessed patient data (with PHI implications)

---

## Networking

### Required Connectivity

| From | To | Protocol | Port |
|---|---|---|---|
| Load Balancer | App Pods | HTTP | 8080 |
| App Pods | MongoDB | TCP | 27017 |
| App Pods | HealthLake | HTTPS | 443 |
| App Pods | S3 | HTTPS | 443 |
| App Pods | DynamoDB | HTTPS | 443 |
| External (consumers) | S3 (presigned URLs) | HTTPS | 443 |
| External | Load Balancer | HTTPS | 443 |
| External | `.well-known/jwks.json` | HTTPS | 443 |

### TLS Requirements

- **External**: TLS 1.2+ required (TLS 1.3 preferred). SHC spec requires BCP 195 compliance.
- **MongoDB**: TLS connection string parameter (`tls=true`)
- **HealthLake**: Always HTTPS (AWS enforced)

### CORS

`@CrossOrigin("*")` is configured on:
- `ShlProtocolController` (manifest, file download, direct access)
- `WellKnownController` (JWKS endpoint)

These are intentionally public per the SHL spec — EHR apps from any origin must be able to call them.

Management endpoints (`ShlManagementController`) do NOT have CORS — they should only be called from your own frontend.

---

## Secrets Management

| Secret | Storage Recommendation | Rotation |
|---|---|---|
| MongoDB credentials | K8s Secret / AWS Secrets Manager | 90 days |
| EC P-256 signing key | K8s Secret / AWS Secrets Manager / HSM | Yearly (add new key to JWKS, keep old for verification) |
| AWS credentials | IAM Role (preferred) / K8s Secret | Per IAM policy |

### Signing Key Rotation

When rotating the SHC signing key:
1. Generate new EC P-256 key pair
2. Add the new public key to `/.well-known/jwks.json` (both old and new)
3. Switch signing to the new key
4. After all previously-issued SHCs expire, remove the old public key

---

## Disaster Recovery

### Backup Strategy

| Component | Backup Method | RPO | RTO |
|---|---|---|---|
| MongoDB | Continuous backup (Atlas) or daily snapshots | 1 hour | 1 hour |
| S3 | Cross-region replication (optional) | 0 (11 9's durability) | Minutes |
| DynamoDB | PITR (enabled for compliance) | 5 minutes | Minutes |
| Signing key | Stored in 2+ secrets managers | 0 | Minutes |
| Application config | Git (env vars in K8s manifests) | 0 | Minutes |

### Data Loss Impact

- **ShlDocument lost**: Affected SHLs become inaccessible (404). Patients must create new links.
- **ShlFileDocument lost**: Affected SHLs return empty manifests. Refresh (L-flag) re-populates. Non-L SHLs need recreation. S3 objects remain but are orphaned.
- **S3 objects lost**: Affected SHLs return empty content. Refresh (L-flag) re-creates. Non-L SHLs need recreation.
- **DynamoDB data lost**: Access log history lost. Restore from PITR. No impact on SHL functionality.
- **Signing key lost**: Cannot issue new SHCs. Existing SHCs remain verifiable if public key is cached by verifiers. Generate new key pair.
- **HealthLake unavailable**: SHL creation and refresh fail with 502. Existing SHLs continue to work (files already in S3).

---

## Performance Benchmarks (Expected)

| Operation | Expected Latency | Bottleneck |
|---|---|---|
| Create SHL (3 categories) | 2-5s | HealthLake fetch + S3 upload |
| Manifest lookup | <50ms | MongoDB query + S3 presign (local crypto) |
| File download (presigned URL) | <100ms | S3 (direct, no server proxy) |
| QR code generation | 100-200ms | ZXing CPU-bound |
| BCrypt passcode verify | 100-300ms | CPU-bound (intentional) |
| SHC signing | <50ms | ES256 is fast |
