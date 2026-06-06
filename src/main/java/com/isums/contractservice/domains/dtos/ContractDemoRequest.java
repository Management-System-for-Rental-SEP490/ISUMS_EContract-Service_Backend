package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.ContractDemoScenario;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record ContractDemoRequest(
        @NotNull UUID contractId,
        @NotNull ContractDemoScenario scenario,
        Instant customEffectiveAt,
        @NotBlank String confirmation
) {
}
