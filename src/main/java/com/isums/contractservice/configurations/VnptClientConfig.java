package com.isums.contractservice.configurations;

import com.isums.contractservice.domains.dtos.VnptEcontractProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(VnptEcontractProperties.class)
public class VnptClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient vnptRestClient(RestClient.Builder builder, VnptEcontractProperties pros) {
        return builder.baseUrl(pros.getBaseUrl()).build();
    }
}
