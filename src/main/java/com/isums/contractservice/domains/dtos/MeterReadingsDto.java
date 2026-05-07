package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record MeterReadingsDto(
        @Nullable @PositiveOrZero Long electricKwh,
        @Nullable @PositiveOrZero Long waterM3,
        @Nullable String note
) {
    public java.util.Map<String, Object> asMap() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        if (electricKwh != null) m.put("electric", electricKwh);
        if (waterM3 != null) m.put("water", waterM3);
        if (note != null) m.put("note", note);
        return m;
    }
}

