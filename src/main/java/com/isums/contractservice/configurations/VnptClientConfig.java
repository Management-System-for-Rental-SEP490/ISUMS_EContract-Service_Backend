package com.isums.contractservice.configurations;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(VnptEcontractProperties.class)
public class VnptClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Primary
    @Bean
    public RestClient vnptRestClient(RestClient.Builder builder, VnptEcontractProperties pros) {
        return builder.baseUrl(pros.getGatewayBaseUrl()).build();
    }

    @Bean
    public RestClient directVnptRestClient(RestClient.Builder builder, VnptEcontractProperties pros) {
        return builder.baseUrl(pros.getBaseUrl()).build();
    }
}