package com.isums.contractservice.infrastructures.grpcs;

import com.isums.contractservice.configurations.BearerTokenCallCredentials;
import com.isums.contractservice.configurations.ServiceAccountTokenProvider;
import com.isums.userservice.grpc.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserGrpcClient {

    private final UserServiceGrpc.UserServiceBlockingStub stub;
    private final ServiceAccountTokenProvider tokenProvider;

    public UserResponse getUserByEmail(String email, String jwtToken) {
        GetUserByEmailRequest req = GetUserByEmailRequest.newBuilder().setEmail(email).build();
        return stub.withCallCredentials(new BearerTokenCallCredentials(jwtToken)).getUserByEmail(req);
    }

    public GetUserRolesResponse getUserRoles(String keycloakId, String jwtToken) {
        GetUserRolesRequest req = GetUserRolesRequest.newBuilder().setKeycloakId(keycloakId).build();
        return stub.withCallCredentials(new BearerTokenCallCredentials(jwtToken)).getUserRoles(req);
    }

    public UserResponse getUserById(String userId) {
        String token = tokenProvider.getToken();
        GetUserByIdRequest req = GetUserByIdRequest.newBuilder().setUserId(userId).build();
        return stub.withCallCredentials(new BearerTokenCallCredentials(token)).getUserById(req);
    }
}
