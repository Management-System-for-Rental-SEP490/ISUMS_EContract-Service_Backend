package com.isums.contractservice.infrastructures.grpcs;

import com.isums.houseservice.grpc.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HouseGrpcClient {

    private final HouseServiceGrpc.HouseServiceBlockingStub houseStub;
    private final TenantServiceGrpc.TenantServiceBlockingStub tenantStub;

    public HouseResponse getHouseById(UUID id) {
        GetHouseRequest req = GetHouseRequest.newBuilder().setHouseId(id.toString()).build();
        return houseStub.getHouseById(req);
    }

    public TenantResponse getTenantByUserId(UUID userId) {
        GetTenantByUserIdRequest req = GetTenantByUserIdRequest.newBuilder().setUserId(userId.toString()).build();
        return tenantStub.getTenantByUserId(req);
    }
}
