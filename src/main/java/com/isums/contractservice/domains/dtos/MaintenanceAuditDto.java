package com.isums.contractservice.domains.dtos;

import java.time.Instant;
import java.util.UUID;

public record MaintenanceAuditDto(
        Long id,
        String action,
        Boolean enabledBefore,
        Boolean enabledAfter,
        String scope,
        String titleVi,
        UUID actorId,
        String actorEmail,
        Instant createdAt
) {
}
