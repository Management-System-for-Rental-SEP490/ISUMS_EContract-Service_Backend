package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VnptProcessDto(
        String processCode,
        String token,
        String processId,
        String reason,
        Boolean reject,
        String otp,
        int signatureDisplayMode,
        String signatureImage,
        Integer signingPage,
        String signingPosition,
        String signatureText,
        Integer fontSize,
        Boolean showReason,
        Boolean confirmTermsConditions
) {
}
