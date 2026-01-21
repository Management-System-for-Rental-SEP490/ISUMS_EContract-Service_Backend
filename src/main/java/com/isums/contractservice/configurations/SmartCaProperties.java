package com.isums.contractservice.configurations;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smartca.base-url")
@Data
public class SmartCaProperties {
    private String baseUrl;
}
