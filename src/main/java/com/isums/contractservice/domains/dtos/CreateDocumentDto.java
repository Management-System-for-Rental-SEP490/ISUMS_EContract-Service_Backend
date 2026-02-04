package com.isums.contractservice.domains.dtos;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateDocumentDto(
        @Nullable FileInfoDto fileInfo,

        @Nullable String subject,
        @Nullable String description,
        int typeId,
        int departmentId,

        @Nullable String no

) {
}
