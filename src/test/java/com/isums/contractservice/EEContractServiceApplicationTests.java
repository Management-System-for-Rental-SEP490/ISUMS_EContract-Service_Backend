package com.isums.contractservice;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires Keycloak/Postgres/Kafka/Redis/S3/VNPT/gRPC infrastructure; run as integration test with Testcontainers")
class EEContractServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
