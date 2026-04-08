package com.isums.contractservice.domains.dtos;

import java.io.Serial;
import java.io.Serializable;
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
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
