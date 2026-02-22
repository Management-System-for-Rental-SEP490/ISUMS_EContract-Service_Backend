package com.isums.contractservice.domains.dtos;

import java.util.UUID;

public record ProcessLoginInfoDto(
        String processId,
        String documentId,
        String documentNo,
        Integer processedByUserId,
        String accessToken,
        String position,
        Integer pageSign,
        boolean isOTP
) {}