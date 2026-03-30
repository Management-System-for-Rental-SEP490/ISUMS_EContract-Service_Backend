package com.isums.contractservice.configurations;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final RemoteRoleJwtConverter jwtConverter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(
                                "/api/econtracts/v3/api-docs",
                                "/api/econtracts/v3/api-docs/**",
                                "/api/econtracts/swagger",
                                "/api/econtracts/swagger/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/api/econtracts/processCode",
                                "/api/econtracts/ready",
                                "/api/econtracts/outsystem",
                                "/api/econtracts/sign",
                                "/api/econtracts/*/cccd",
                                "/api/econtracts/*/cccd-status",
                                "/api/econtracts/ws",
                                "/api/econtracts/ws/**",
                                "/api/econtracts/*/pdf-url",
                                "/api/econtracts/*/tenant-cancel"
                        ).permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .build();
    }
}
