package com.isums.contractservice.configurations;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiStripServersConfig {

    @Bean
    public OpenApiCustomizer relativeServers() {
        return openApi -> openApi.setServers(
                List.of(new io.swagger.v3.oas.models.servers.Server().url(""))
        );
    }
}
