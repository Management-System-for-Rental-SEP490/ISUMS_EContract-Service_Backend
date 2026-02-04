package com.isums.contractservice.domains.dtos;

import java.time.Instant;
import java.util.UUID;

public record VnptProcessItem(
        UUID id,
        Instant createdDate,
        int comId,
        boolean isOrder,
        int orderNo,
        int pageSign,
        String position,
        VnptStatusDto displayType,
        VnptStatusDto accessPermission,
        VnptStatusDto status,
        int processedByUserId,
        String documentId
) {}
