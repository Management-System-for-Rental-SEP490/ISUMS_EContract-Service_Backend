package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.MaintenanceScope;
import com.isums.contractservice.domains.enums.MaintenanceSeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record MaintenanceUpdateRequest(
        @NotNull Boolean enabled,
        @NotNull MaintenanceScope scope,
        MaintenanceSeverity severity,
        @NotBlank @Size(max = 200) String titleVi,
        @Size(max = 200) String titleEn,
        @Size(max = 200) String titleJa,
        @NotBlank String messageVi,
        String messageEn,
        String messageJa,
        Instant scheduledStart,
        Instant scheduledEnd,
        Boolean allowReadOnly,
        @Size(max = 255) String contactEmail,
        @Size(max = 32) String contactPhone,
        @Size(max = 255) String statusPageUrl
) {
}
