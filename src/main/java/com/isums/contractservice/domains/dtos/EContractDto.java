package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.EContractStatus;

import java.time.Instant;
import java.util.UUID;

public record EContractDto(
        UUID id,
        String documentId,
        String documentNo,
        UUID userId,
        UUID tenantId,
        String html,
        String name,
        String snapshotKey,
        UUID houseId,
        Instant startAt,
        Instant endAt,
        EContractStatus status,
        String pdfUrl,
        UUID createdBy,
        Instant createdAt
) {

    public EContractDto updatePdfUrl(String pdfUrl) {
        return new EContractDto(
                this.id,
                this.documentId,
                this.documentNo,
                this.userId,
                this.tenantId,
                this.html,
                this.name,
                this.snapshotKey,
                this.houseId,
                this.startAt,
                this.endAt,
                this.status,
                pdfUrl,
                this.createdBy,
                this.createdAt
        );
    }
}
