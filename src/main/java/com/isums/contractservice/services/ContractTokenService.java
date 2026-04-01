package com.isums.contractservice.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContractTokenService {

    private final StringRedisTemplate redis;

    @Value("${app.contract.token-ttl-hours:24}")
    private long ttlHours;

    private static final String PREFIX = "contract:token:";

    public String generateToken(UUID contractId, UUID tenantUserId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String value = contractId + ":" + tenantUserId;

        redis.opsForValue().set(
                PREFIX + token,
                value,
                Duration.ofHours(ttlHours)
        );

        log.info("[ContractToken] Generated token contractId={} ttl={}h", contractId, ttlHours);
        return token;
    }

    public void validateToken(String token, UUID contractId) {
        if (token == null || token.isBlank())
            throw new IllegalArgumentException("Token không được để trống.");

        String value = redis.opsForValue().get(PREFIX + token);
        if (value == null)
            throw new IllegalArgumentException("Token không hợp lệ hoặc đã hết hạn.");

        String[] parts = value.split(":");
        if (parts.length != 2)
            throw new IllegalArgumentException("Token bị hỏng.");

        UUID tokenContractId = UUID.fromString(parts[0]);
        UUID tenantUserId    = UUID.fromString(parts[1]);

        if (!tokenContractId.equals(contractId))
            throw new IllegalArgumentException("Token không khớp với hợp đồng.");

    }

    public void invalidateToken(String token) {
        if (token == null || token.isBlank()) return;
        Boolean deleted = redis.delete(PREFIX + token);
        log.info("[ContractToken] Invalidated token deleted={}", deleted);
    }

    public void invalidateAllByContract(UUID contractId) {
        log.debug("[ContractToken] invalidateAllByContract contractId={} — no-op", contractId);
    }
}