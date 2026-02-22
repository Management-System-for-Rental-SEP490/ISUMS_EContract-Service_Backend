package com.isums.contractservice.infrastructures.grpcs;

import com.isums.userservice.grpc.GetUserByEmailRequest;
import com.isums.userservice.grpc.UserResponse;
import com.isums.userservice.grpc.UserServiceGrpc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserGrpcClient {

    private final UserServiceGrpc.UserServiceBlockingStub stub;

    public UserResponse getUserByEmail(String email) {
        GetUserByEmailRequest req = GetUserByEmailRequest.newBuilder().setEmail(email).build();
        return stub.getUserByEmail(req);
    }
}
