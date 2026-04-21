package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record DetailedAddressDto(
        @Nullable String line,
        @NotBlank String ward,
        @NotBlank String district,
        @NotBlank String province,
        @Nullable String country
) {
    public java.util.Map<String, String> asMap() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (line != null) m.put("line", line);
        m.put("ward", ward);
        m.put("district", district);
        m.put("province", province);
        if (country != null) m.put("country", country);
        return m;
    }
}
