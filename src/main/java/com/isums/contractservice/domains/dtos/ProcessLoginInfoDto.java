package com.isums.contractservice.domains.dtos;

public record ProcessLoginInfoDto(
        String processId,
        String documentId,
        String documentNo,
        Integer processedByUserId,
        String accessToken,
        String position,
        Integer pageSign,
        String pdfUrl
) {
    public ProcessLoginInfoDto updatePdfUrl(String newPdfUrl) {
        return new ProcessLoginInfoDto(
                this.processId,
                this.documentId,
                this.documentNo,
                this.processedByUserId,
                this.accessToken,
                this.position,
                this.pageSign,
                newPdfUrl
        );
    }
}