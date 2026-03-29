package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TenantSignaturePosition(
        @NotNull @Min(0) Integer x1,
        @NotNull @Min(0) Integer y1,
        @NotNull @Min(0) Integer x2,
        @NotNull @Min(0) Integer y2,
        @NotNull @Min(1) Integer page
) {
    public String toVnptPositionString() {
        return x1 + "," + y1 + "," + x2 + "," + y2;
    }
}