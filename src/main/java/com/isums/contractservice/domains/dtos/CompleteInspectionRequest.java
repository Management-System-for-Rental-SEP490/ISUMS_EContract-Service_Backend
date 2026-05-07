package com.isums.contractservice.domains.dtos;

import java.util.List;

public record CompleteInspectionRequest(
        String notes,
        Long deductionAmount,
        List<String> photoUrls
) {
}
