package com.isums.contractservice.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractTokenService")
class ContractTokenServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks private ContractTokenService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "ttlHours", 24L);
    }

    @Nested
    @DisplayName("generateToken")
    class Generate {

        @Test
        @DisplayName("writes contract:tenant pair with TTL and returns 32-hex token")
        void writes() {
            when(redis.opsForValue()).thenReturn(valueOps);
            UUID contractId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();

            String token = service.generateToken(contractId, tenantId);

            assertThat(token).hasSize(32);
            verify(valueOps).set(
                    eq("contract:token:" + token),
                    eq(contractId + ":" + tenantId),
                    eq(Duration.ofHours(24)));
        }
    }

    @Nested
    @DisplayName("validateToken")
    class Validate {

        @Test
        @DisplayName("throws when token null")
        void nullToken() {
            assertThatThrownBy(() -> service.validateToken(null, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không được để trống");
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("throws when token blank")
        void blankToken() {
            assertThatThrownBy(() -> service.validateToken("   ", UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws when Redis returns null (expired)")
        void expired() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("contract:token:t1")).thenReturn(null);

            assertThatThrownBy(() -> service.validateToken("t1", UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hết hạn");
        }

        @Test
        @DisplayName("throws when redis value corrupt")
        void corrupt() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("contract:token:t2")).thenReturn("only-one-segment");

            assertThatThrownBy(() -> service.validateToken("t2", UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bị hỏng");
        }

        @Test
        @DisplayName("throws when contract mismatches")
        void mismatch() {
            when(redis.opsForValue()).thenReturn(valueOps);
            UUID tokenContractId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(valueOps.get("contract:token:t3")).thenReturn(tokenContractId + ":" + tenantId);

            assertThatThrownBy(() -> service.validateToken("t3", UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("không khớp");
        }

        @Test
        @DisplayName("passes when token matches")
        void matches() {
            when(redis.opsForValue()).thenReturn(valueOps);
            UUID contractId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            when(valueOps.get("contract:token:t4")).thenReturn(contractId + ":" + tenantId);

            service.validateToken("t4", contractId);
        }
    }

    @Nested
    @DisplayName("invalidateToken")
    class Invalidate {

        @Test
        @DisplayName("no-op on null/blank")
        void noOp() {
            service.invalidateToken(null);
            service.invalidateToken("");
            verifyNoInteractions(redis);
        }

        @Test
        @DisplayName("deletes key when token provided")
        void deletes() {
            service.invalidateToken("tok-1");
            verify(redis).delete("contract:token:tok-1");
        }
    }

    @Test
    @DisplayName("invalidateAllByContract is a no-op placeholder")
    void invalidateAll() {
        service.invalidateAllByContract(UUID.randomUUID());
        verifyNoInteractions(redis);
    }
}
