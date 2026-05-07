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
    public VnptProcessDto withToken(String newToken) {
        return new VnptProcessDto(
                processCode, newToken, processId, reason, reject, otp, signatureDisplayMode, signatureImage, signingPage, signingPosition,
                signatureText, fontSize, showReason, confirmTermsConditions
        );
    }

    public VnptProcessDto withNormalizedPosition() {
        if (signingPosition == null || signingPosition.isBlank()) return this;
        String[] parts = signingPosition.split(",");
        if (parts.length != 4) return this;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String trimmed = parts[i].trim();
            if (trimmed.isEmpty()) return this;
            try {
                long rounded = Math.round(Double.parseDouble(trimmed));
                if (i > 0) sb.append(',');
                sb.append(rounded);
            } catch (NumberFormatException e) {
                return this;
            }
        }
        String normalized = sb.toString();
        if (normalized.equals(signingPosition)) return this;
        return new VnptProcessDto(
                processCode, token, processId, reason, reject, otp, signatureDisplayMode, signatureImage, signingPage, normalized,
                signatureText, fontSize, showReason, confirmTermsConditions
        );
    }
}
