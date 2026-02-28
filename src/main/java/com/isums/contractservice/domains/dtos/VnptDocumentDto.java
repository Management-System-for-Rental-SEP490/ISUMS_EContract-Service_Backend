package com.isums.contractservice.domains.dtos;

import java.math.BigDecimal;
import java.util.List;

public record VnptDocumentDto(
        String id,
        String createdDate,
        String lastModifiedDate,
        String expiryDate,
        String completedDate,
        String validFrom,
        String validTo,
        String documentDate,
        BigDecimal contractValue,
        String customerCode,
        String customerInformation,
        String no,
        String subject,
        String downloadUrl,
        VnptStatusDto status,
        VnptProcessItem waitingProcess,
        List<VnptProcess> processes,
        String positionA,
        String positionB,
        Integer pageSign,
        byte[] pdfBytes,
        String fileName
) {
    public void withSignMeta(String posA, String posB) {
        new VnptDocumentDto(
                id, createdDate, lastModifiedDate, expiryDate, completedDate,
                validFrom, validTo, documentDate, contractValue,
                customerCode, customerInformation, no, subject, downloadUrl,
                status, waitingProcess, processes,
                posA, posB, pageSign, pdfBytes, fileName
        );
    }
}
