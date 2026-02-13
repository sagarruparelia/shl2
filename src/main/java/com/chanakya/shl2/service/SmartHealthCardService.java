package com.chanakya.shl2.service;

import com.chanakya.shl2.config.ShlProperties;
import com.chanakya.shl2.crypto.JwsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.zip.Deflater;

@Service
public class SmartHealthCardService {

    private final ShlProperties properties;
    private final JwsService jwsService;
    private final ObjectMapper objectMapper;

    public SmartHealthCardService(ShlProperties properties, JwsService jwsService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.jwsService = jwsService;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a SMART Health Card from a FHIR Bundle JSON.
     * Returns the SHC JSON: {"verifiableCredential": ["<JWS>"]}
     */
    public Mono<String> createHealthCard(String fhirBundleJson) {
        return Mono.fromCallable(() -> {
            JsonNode bundle = objectMapper.readTree(fhirBundleJson);

            // Minify the FHIR bundle for QR
            JsonNode minifiedBundle = minifyBundle(bundle);

            // Build VC payload
            ObjectNode vcPayload = objectMapper.createObjectNode();
            vcPayload.put("iss", properties.shc().issuerUrl());
            vcPayload.put("nbf", Instant.now().getEpochSecond());

            ObjectNode vc = vcPayload.putObject("vc");
            ArrayNode types = vc.putArray("type");
            types.add("https://smarthealth.cards#health-card");
            types.add("https://smarthealth.cards#covid19");

            ObjectNode credentialSubject = vc.putObject("credentialSubject");
            credentialSubject.put("fhirVersion", "4.0.1");
            credentialSubject.set("fhirBundle", minifiedBundle);

            // Serialize, DEFLATE, and sign
            String payloadJson = objectMapper.writeValueAsString(vcPayload);
            byte[] deflated = deflate(payloadJson.getBytes(StandardCharsets.UTF_8));
            String jws = jwsService.sign(deflated);

            // Wrap as SHC
            ObjectNode shc = objectMapper.createObjectNode();
            ArrayNode credentials = shc.putArray("verifiableCredential");
            credentials.add(jws);

            return objectMapper.writeValueAsString(shc);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Minifies a FHIR Bundle for QR code size optimization per SHC spec.
     */
    private JsonNode minifyBundle(JsonNode bundle) {
        ObjectNode minified = (ObjectNode) bundle.deepCopy();

        JsonNode entries = minified.path("entry");
        if (entries.isArray()) {
            int index = 0;
            for (JsonNode entry : entries) {
                // Replace fullUrl with resource:N
                if (entry instanceof ObjectNode entryObj) {
                    entryObj.put("fullUrl", "resource:" + index);

                    JsonNode resource = entry.path("resource");
                    if (resource instanceof ObjectNode resourceObj) {
                        // Strip Resource.id
                        resourceObj.remove("id");

                        // Strip Resource.meta (keep .meta.security)
                        JsonNode meta = resourceObj.path("meta");
                        if (meta instanceof ObjectNode metaObj) {
                            JsonNode security = metaObj.path("security");
                            if (security.isMissingNode() || security.isEmpty()) {
                                resourceObj.remove("meta");
                            } else {
                                ObjectNode newMeta = objectMapper.createObjectNode();
                                newMeta.set("security", security);
                                resourceObj.set("meta", newMeta);
                            }
                        }

                        // Strip DomainResource.text
                        resourceObj.remove("text");

                        // Strip CodeableConcept.text and Coding.display recursively
                        stripCodeableConceptText(resourceObj);
                    }

                    index++;
                }
            }
        }

        return minified;
    }

    /**
     * Recursively strips CodeableConcept.text and Coding.display from a resource.
     */
    private void stripCodeableConceptText(JsonNode node) {
        if (node instanceof ObjectNode objNode) {
            // Check if this looks like a CodeableConcept (has "coding" array)
            if (objNode.has("coding") && objNode.path("coding").isArray()) {
                objNode.remove("text");
                for (JsonNode coding : objNode.path("coding")) {
                    if (coding instanceof ObjectNode codingObj) {
                        codingObj.remove("display");
                    }
                }
            }

            // Recurse into child nodes
            for (String fieldName : objNode.propertyNames()) {
                JsonNode child = objNode.get(fieldName);
                stripCodeableConceptText(child);
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (JsonNode element : arrayNode) {
                stripCodeableConceptText(element);
            }
        }
    }

    /**
     * DEFLATE compress (raw RFC1951, no zlib headers).
     */
    private byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true); // true = raw deflate
        deflater.setInput(input);
        deflater.finish();

        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            out.write(buffer, 0, count);
        }
        deflater.end();
        return out.toByteArray();
    }
}
