package com.chanakya.shl2.service;

import com.chanakya.shl2.config.ShlProperties;
import com.chanakya.shl2.exception.HealthLakeException;
import com.chanakya.shl2.model.enums.FhirCategory;
import com.chanakya.shl2.model.fhir.FhirBundleWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import java.time.Instant;
import java.util.List;

@Service
public class HealthLakeService {

    private final ShlProperties properties;
    private final ObjectMapper objectMapper;
    private WebClient webClient;
    private DefaultCredentialsProvider credentialsProvider;

    public HealthLakeService(ShlProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        String datastoreId = properties.aws().healthlakeDatastoreId();
        String region = properties.aws().region();
        String baseUrl = String.format("https://healthlake.%s.amazonaws.com/datastore/%s/r4",
                region, datastoreId);

        this.credentialsProvider = DefaultCredentialsProvider.create();
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/fhir+json")
                .build();
    }

    /**
     * Fetches FHIR resources for all requested categories.
     */
    public Flux<FhirBundleWrapper> fetchResourcesByCategory(
            String patientId,
            List<FhirCategory> categories,
            Instant from,
            Instant to) {
        return Flux.fromIterable(categories)
                .flatMap(category -> fetchCategory(patientId, category, from, to));
    }

    private Mono<FhirBundleWrapper> fetchCategory(
            String patientId,
            FhirCategory category,
            Instant from,
            Instant to) {

        if (category.isDirectRead()) {
            // Direct read for Patient
            return fetchResource("/" + category.getFhirResourceType() + "/" + patientId)
                    .map(json -> wrapAsBundle(category, json))
                    .onErrorMap(e -> new HealthLakeException(
                            "Failed to fetch " + category.name() + " for patient " + patientId, e));
        }

        String searchParams = category.buildSearchParams(patientId);
        if (from != null) {
            searchParams += "&date=ge" + from;
        }
        if (to != null) {
            searchParams += "&date=le" + to;
        }

        String path = "/" + category.getFhirResourceType() + "?" + searchParams;

        return fetchBundleWithPagination(path)
                .map(bundleJson -> {
                    int count = countResources(bundleJson);
                    if (category == FhirCategory.CLINICAL_DOCUMENTS) {
                        bundleJson = resolveDocumentReferenceBinaries(bundleJson);
                    }
                    return FhirBundleWrapper.builder()
                            .category(category)
                            .bundleJson(bundleJson)
                            .resourceCount(count)
                            .build();
                })
                .onErrorMap(e -> !(e instanceof HealthLakeException),
                        e -> new HealthLakeException(
                                "Failed to fetch " + category.name() + " for patient " + patientId, e));
    }

    private Mono<String> fetchResource(String path) {
        return webClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(String.class);
    }

    private Mono<String> fetchBundleWithPagination(String path) {
        return fetchResource(path)
                .expand(bundleJson -> {
                    try {
                        JsonNode bundle = objectMapper.readTree(bundleJson);
                        JsonNode links = bundle.path("link");
                        for (JsonNode link : links) {
                            if ("next".equals(link.path("relation").asText())) {
                                String nextUrl = link.path("url").asText();
                                return fetchResource(extractPath(nextUrl));
                            }
                        }
                    } catch (Exception e) {
                        return Mono.error(new HealthLakeException("Failed to parse bundle for pagination", e));
                    }
                    return Mono.empty();
                })
                .reduce(this::mergeBundles);
    }

    private String mergeBundles(String bundle1, String bundle2) {
        try {
            ObjectNode b1 = (ObjectNode) objectMapper.readTree(bundle1);
            JsonNode b2 = objectMapper.readTree(bundle2);

            ArrayNode entries1 = b1.has("entry")
                    ? (ArrayNode) b1.get("entry")
                    : b1.putArray("entry");

            JsonNode entries2 = b2.path("entry");
            if (entries2.isArray()) {
                for (JsonNode entry : entries2) {
                    entries1.add(entry);
                }
            }

            // Update total
            int total = entries1.size();
            b1.put("total", total);

            // Remove pagination links
            b1.remove("link");

            return objectMapper.writeValueAsString(b1);
        } catch (Exception e) {
            throw new HealthLakeException("Failed to merge bundles", e);
        }
    }

    private FhirBundleWrapper wrapAsBundle(FhirCategory category, String resourceJson) {
        try {
            ObjectNode bundle = objectMapper.createObjectNode();
            bundle.put("resourceType", "Bundle");
            bundle.put("type", "searchset");
            bundle.put("total", 1);

            ArrayNode entries = bundle.putArray("entry");
            ObjectNode entry = entries.addObject();
            entry.set("resource", objectMapper.readTree(resourceJson));

            String bundleJson = objectMapper.writeValueAsString(bundle);
            return FhirBundleWrapper.builder()
                    .category(category)
                    .bundleJson(bundleJson)
                    .resourceCount(1)
                    .build();
        } catch (Exception e) {
            throw new HealthLakeException("Failed to wrap resource as bundle", e);
        }
    }

    /**
     * Resolves Binary references in DocumentReference resources.
     * For each content[].attachment with a relative Binary URL, fetches and embeds the data.
     */
    private String resolveDocumentReferenceBinaries(String bundleJson) {
        try {
            ObjectNode bundle = (ObjectNode) objectMapper.readTree(bundleJson);
            JsonNode entries = bundle.path("entry");

            if (!entries.isArray()) {
                return bundleJson;
            }

            for (JsonNode entry : entries) {
                JsonNode resource = entry.path("resource");
                if (!"DocumentReference".equals(resource.path("resourceType").asText())) {
                    continue;
                }

                JsonNode contentArray = resource.path("content");
                if (!contentArray.isArray()) {
                    continue;
                }

                for (JsonNode content : contentArray) {
                    ObjectNode attachment = (ObjectNode) content.path("attachment");
                    if (attachment.isMissingNode()) {
                        continue;
                    }

                    String url = attachment.path("url").asText(null);
                    if (url != null && url.matches("Binary/[\\w-]+")) {
                        // Fetch the Binary resource
                        try {
                            String binaryJson = fetchResource("/" + url).block();
                            if (binaryJson != null) {
                                JsonNode binary = objectMapper.readTree(binaryJson);
                                String data = binary.path("data").asText(null);
                                String contentType = binary.path("contentType").asText(null);
                                if (data != null) {
                                    attachment.put("data", data);
                                }
                                if (contentType != null) {
                                    attachment.put("contentType", contentType);
                                }
                                attachment.remove("url");
                            }
                        } catch (Exception e) {
                            // Keep the reference as-is if binary fetch fails
                        }
                    }
                }
            }

            return objectMapper.writeValueAsString(bundle);
        } catch (Exception e) {
            return bundleJson; // Return original if parsing fails
        }
    }

    private int countResources(String bundleJson) {
        try {
            JsonNode bundle = objectMapper.readTree(bundleJson);
            JsonNode total = bundle.path("total");
            if (!total.isMissingNode()) {
                return total.asInt();
            }
            JsonNode entries = bundle.path("entry");
            return entries.isArray() ? entries.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String extractPath(String fullUrl) {
        // Extract the path portion from a full HealthLake URL
        int r4Index = fullUrl.indexOf("/r4");
        if (r4Index >= 0) {
            return fullUrl.substring(r4Index + 3);
        }
        return fullUrl;
    }
}
