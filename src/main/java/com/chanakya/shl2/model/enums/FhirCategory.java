package com.chanakya.shl2.model.enums;

public enum FhirCategory {
    PATIENT_DEMOGRAPHICS("Patient", null),
    CONDITIONS("Condition", "patient={id}"),
    MEDICATIONS("MedicationRequest", "patient={id}"),
    LAB_RESULTS("Observation", "patient={id}&category=laboratory"),
    VITAL_SIGNS("Observation", "patient={id}&category=vital-signs"),
    IMMUNIZATIONS("Immunization", "patient={id}"),
    ALLERGIES("AllergyIntolerance", "patient={id}"),
    PROCEDURES("Procedure", "patient={id}"),
    DIAGNOSTIC_REPORTS("DiagnosticReport", "patient={id}"),
    ENCOUNTERS("Encounter", "patient={id}"),
    CLINICAL_DOCUMENTS("DocumentReference", "patient={id}&category=clinical-note");

    private final String fhirResourceType;
    private final String searchParamTemplate;

    FhirCategory(String fhirResourceType, String searchParamTemplate) {
        this.fhirResourceType = fhirResourceType;
        this.searchParamTemplate = searchParamTemplate;
    }

    public String getFhirResourceType() {
        return fhirResourceType;
    }

    public String getSearchParamTemplate() {
        return searchParamTemplate;
    }

    public String buildSearchParams(String patientId) {
        if (searchParamTemplate == null) {
            return null;
        }
        return searchParamTemplate.replace("{id}", patientId);
    }

    public boolean isDirectRead() {
        return searchParamTemplate == null;
    }
}
