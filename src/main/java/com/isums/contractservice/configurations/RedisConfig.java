package com.isums.contractservice.configurations;

import common.paginations.configurations.IsumCacheConfigurer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class RedisConfig implements IsumCacheConfigurer {
    @Override
    public Map<String, Duration> cacheTtls() {
        return Map.of(
                "vnptToken", Duration.ofHours(23).plusMinutes(30),
                "allEContracts", Duration.ofHours(23).plusMinutes(30),
                "vnptProcessCode", Duration.ofMinutes(15)
        );
    }
}

