package com.isums.contractservice.domains.dtos;

import java.util.List;
import java.util.UUID;

public record VnptUserUpsert(

        String code,
        String userName,
        String name,
        String email,
        String phone,

        int receiveOtpMethod,
        int receiveNotificationMethod,
        int signMethod,

        boolean signConfirmationEnabled,
        boolean generateSelfSignedCertEnabled,

        int status,

        Integer receiveInfoAccountMethod,

        List<Integer> departmentIds,
        List<UUID> roleIds

) {
}
