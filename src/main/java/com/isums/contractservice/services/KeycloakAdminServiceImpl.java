package com.isums.contractservice.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class KeycloakAdminServiceImpl {

    @Value("${keycloak.server-url}")
    private String serverUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.admin.client-id}")
    private String clientId;

    @Value("${keycloak.admin.client-secret}")
    private String clientSecret;

    private Keycloak buildClient() {
        return KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm("master")
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
    }

    public String activateUser(String keycloakUserId) {
        try (Keycloak kc = buildClient()) {
            UserResource userResource = kc.realm(realm).users().get(keycloakUserId);
            UserRepresentation user = userResource.toRepresentation();

            user.setEnabled(true);
            userResource.update(user);

            String tempPassword = generateTempPassword();
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(tempPassword);
            credential.setTemporary(true);
            userResource.resetPassword(credential);

            log.info("Activated Keycloak user keycloakId={}", keycloakUserId);
            return tempPassword;
        } catch (Exception e) {
            log.error("Failed to activate Keycloak user keycloakId={}", keycloakUserId, e);
            throw new IllegalStateException("Failed to activate Keycloak user: " + e.getMessage());
        }
    }

    private String generateTempPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        var random = new java.security.SecureRandom();
        return random.ints(10, 0, chars.length())
                .mapToObj(i -> String.valueOf(chars.charAt(i)))
                .collect(java.util.stream.Collectors.joining());
    }
}
