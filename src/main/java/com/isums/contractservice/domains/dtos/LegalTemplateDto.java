package com.isums.contractservice.domains.dtos;

import java.time.Instant;
import java.util.UUID;

public record LegalTemplateDto(
        UUID id,
        String templateKey,
        String lang,
        String text,
        Instant effectiveAt,
        Instant expiredAt,
        String note,
        UUID createdBy,
        UUID expiredBy,
        Instant createdAt,
        Instant updatedAt
) {}
