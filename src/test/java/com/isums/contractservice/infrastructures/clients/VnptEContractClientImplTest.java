package com.isums.contractservice.infrastructures.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.contractservice.configurations.VnptEcontractProperties;
import com.isums.contractservice.domains.dtos.CreateDocumentDto;
import com.isums.contractservice.domains.dtos.FileInfoDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("VnptEContractClientImpl")
class VnptEContractClientImplTest {

    private MockRestServiceServer gatewayServer;
    private MockRestServiceServer directServer;
    private VnptEContractClientImpl client;

    @BeforeEach
    void setUp() {
        VnptEcontractProperties props = new VnptEcontractProperties();
        props.setGatewayBaseUrl("https://gateway.test");
        props.setBaseUrl("https://vnpt.test");
        props.setGatewayToken("internal-token");
        props.setUserName("demo-user");
        props.setPassword("demo-pass");

        RestClient.Builder gatewayBuilder = RestClient.builder().baseUrl(props.getGatewayBaseUrl());
        RestClient.Builder directBuilder = RestClient.builder().baseUrl(props.getBaseUrl());

        gatewayServer = MockRestServiceServer.bindTo(gatewayBuilder).build();
        directServer = MockRestServiceServer.bindTo(directBuilder).build();

        client = new VnptEContractClientImpl(
                gatewayBuilder.build(),
                directBuilder.build(),
                props,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("falls back to direct password-login when gateway returns 502")
    void getTokenFallsBackToDirectOnGateway502() {
        gatewayServer.expect(once(), requestTo("https://gateway.test/internal/vnpt/forward"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("bad gateway"));

        directServer.expect(once(), requestTo("https://vnpt.test/api/v2/auth/password-login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"token":"direct-token"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getToken()).isEqualTo("direct-token");

        gatewayServer.verify();
        directServer.verify();
    }

    @Test
    @DisplayName("falls back to direct multipart createDocument when gateway returns 502")
    void createDocumentFallsBackToDirectMultipartOnGateway502() {
        gatewayServer.expect(once(), requestTo("https://gateway.test/internal/vnpt/forward-multipart"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("bad gateway"));

        directServer.expect(once(), requestTo("https://vnpt.test/api/documents/create"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"id":"doc-1","subject":"Contract test"}}
                        """, MediaType.APPLICATION_JSON));

        CreateDocumentDto create = new CreateDocumentDto(
                new FileInfoDto(null, "pdf".getBytes(), "contract.pdf"),
                "Contract test",
                "desc",
                1,
                2,
                "HD-001"
        );

        var result = client.createDocument("bearer-token", create);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().id()).isEqualTo("doc-1");

        gatewayServer.verify();
        directServer.verify();
    }
}
