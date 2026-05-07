package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateLegalTemplateRequest(
        @NotBlank @Size(max = 80)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]+$",
                message = "templateKey must be UPPER_SNAKE_CASE")
        String templateKey,

        @NotBlank
        @Pattern(regexp = "^(vi|en|ja)$",
                message = "lang must be one of: vi, en, ja")
        String lang,

        @NotBlank @Size(min = 50, max = 8000)
        String text,

        Instant effectiveAt,

        @Size(max = 500)
        String note
) {}
