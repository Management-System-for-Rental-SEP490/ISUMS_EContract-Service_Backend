package com.isums.contractservice.domains.events;

import lombok.Builder;

import java.util.UUID;

@Builder
public record UserActivatedEvent(
        UUID userId,
        String email,
        String name,
        String tempPassword
) {
}
