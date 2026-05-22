package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TranslateRequest(
        @NotBlank String sourceText,
        @NotBlank @Pattern(regexp = "EN|JA") String targetLang
) {
}
