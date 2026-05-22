package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.MaintenanceScope;
import com.isums.contractservice.domains.enums.MaintenanceSeverity;

import java.io.Serializable;
import java.time.Instant;

public record MaintenanceStatusDto(
        Boolean enabled,
        MaintenanceScope scope,
        MaintenanceSeverity severity,
        String titleVi,
        String titleEn,
        String titleJa,
        String messageVi,
        String messageEn,
        String messageJa,
        Instant scheduledStart,
        Instant scheduledEnd,
        Boolean allowReadOnly,
        String contactEmail,
        String contactPhone,
        String statusPageUrl,
        Integer version,
        Instant updatedAt
) implements Serializable {
}
