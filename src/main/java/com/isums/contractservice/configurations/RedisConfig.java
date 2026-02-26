package com.isums.contractservice.configurations;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory cf) {

        JacksonJsonRedisSerializer<Object> valueSerializer = new JacksonJsonRedisSerializer<>(Object.class);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer))
                .disableCachingNullValues();

        return RedisCacheManager.builder(cf)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(5)))
                .withCacheConfiguration("vnptToken", base.entryTtl(Duration.ofHours(23).plusMinutes(30)))
                .withCacheConfiguration("allEContracts", base.entryTtl(Duration.ofHours(23).plusMinutes(30)))
                .withCacheConfiguration("vnptProcessCode", base.entryTtl(Duration.ofMinutes(15)))
                .build();
    }
}

