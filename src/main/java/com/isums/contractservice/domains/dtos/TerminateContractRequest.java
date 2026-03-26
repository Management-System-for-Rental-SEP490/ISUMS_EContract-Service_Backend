package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotBlank;

public record TerminateContractRequest(@NotBlank String reason) {
}
