package com.isums.contractservice.domains.dtos;

import lombok.Builder;

import java.util.UUID;

@Builder
public record UserActivatedEvent(
        UUID userId,
        String email,
        String name,
        String tempPassword,
        String loginUrl
) {
}
