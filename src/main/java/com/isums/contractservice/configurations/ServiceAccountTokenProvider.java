package com.isums.contractservice.configurations;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class ServiceAccountTokenProvider {

    @Value("${keycloak.server-url}")
    private String serverUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    private final RestClient restClient = RestClient.create();

    public String getToken() {
        String url = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";

        String response = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + clientSecret)
                .retrieve()
                .body(String.class);

        try {
            JsonNode node = new ObjectMapper().readTree(response);
            return node.get("access_token").asString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get service account token", e);
        }
    }
}