package com.isums.contractservice.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MagicLinkTokenService {

    private final StringRedisTemplate redis;
    private static final SecureRandom RNG = new SecureRandom();
    @Value("${magiclink.prefix:econtract:magic:}")
    private String prefix;
    @Value("${magiclink.ttl-seconds:900}")
    private long ttlSeconds;

    public String create(UUID contractId, UUID tenantId) {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        String value = contractId + "|" + tenantId;

        redis.opsForValue().set(key(token), value, Duration.ofSeconds(ttlSeconds));

        return token;
    }

    public Optional<MagicPayload> verify(String token) {
        String value = redis.opsForValue().getAndDelete(key(token));
        if (value == null || value.isBlank()) return Optional.empty();

        String[] parts = value.split("\\|");
        if (parts.length != 2) return Optional.empty();

        return Optional.of(new MagicPayload(UUID.fromString(parts[0]), UUID.fromString(parts[1])));
    }

    private String key(String token) {
        return prefix + token;
    }

    public record MagicPayload(UUID contractId, UUID tenantId) {
    }
}
