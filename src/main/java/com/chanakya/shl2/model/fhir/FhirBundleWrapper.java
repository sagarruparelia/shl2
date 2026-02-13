package com.chanakya.shl2.model.fhir;

import com.chanakya.shl2.model.enums.FhirCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FhirBundleWrapper {

    private FhirCategory category;
    private String bundleJson; // Raw FHIR Bundle JSON
    private int resourceCount;
}
