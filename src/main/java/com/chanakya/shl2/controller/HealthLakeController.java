package com.chanakya.shl2.controller;

import com.chanakya.shl2.model.enums.FhirCategory;
import com.chanakya.shl2.model.fhir.FhirBundleWrapper;
import com.chanakya.shl2.service.HealthLakeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// TODO: Add authentication (OAuth2/JWT) — HealthLake endpoints must be protected
@RestController
@RequestMapping("/api/healthlake")
public class HealthLakeController {

    private final HealthLakeService healthLakeService;

    public HealthLakeController(HealthLakeService healthLakeService) {
        this.healthLakeService = healthLakeService;
    }

    /**
     * List available FHIR categories.
     */
    @GetMapping("/categories")
    public Mono<List<Map<String, String>>> getCategories() {
        List<Map<String, String>> categories = Arrays.stream(FhirCategory.values())
                .map(cat -> Map.of(
                        "name", cat.name(),
                        "resourceType", cat.getFhirResourceType()
                ))
                .collect(Collectors.toList());
        return Mono.just(categories);
    }

    /**
     * Preview FHIR resources for a patient and set of categories.
     */
    @GetMapping("/preview")
    public Flux<FhirBundleWrapper> previewResources(
            @RequestParam String patientId,
            @RequestParam List<FhirCategory> categories,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return healthLakeService.fetchResourcesByCategory(patientId, categories, from, to);
    }
}
