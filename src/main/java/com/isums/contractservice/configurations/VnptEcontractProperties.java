package com.isums.contractservice.configurations;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "econtract")
@Data
public class VnptEcontractProperties {
    private String baseUrl;
    private String userName;
    private String password;

    private String gatewayBaseUrl;
    private String gatewayToken;
}
