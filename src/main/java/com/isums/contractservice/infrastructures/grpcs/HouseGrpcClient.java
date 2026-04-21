package com.isums.contractservice.infrastructures.grpcs;

import com.isums.houseservice.grpc.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HouseGrpcClient {

    private final HouseServiceGrpc.HouseServiceBlockingStub houseStub;
    private final TenantServiceGrpc.TenantServiceBlockingStub tenantStub;

    public HouseResponse getHouseById(UUID id) {
        GetHouseRequest req = GetHouseRequest.newBuilder().setHouseId(id.toString()).build();
        return houseStub.getHouseById(req);
    }

    public ListHouseResponse getAllHouseByUser(UUID userId) {
        GetHouseByUserRequest req = GetHouseByUserRequest.newBuilder()
                .setUserId(userId.toString()).build();
        return houseStub.getAllHouseByUser(req);
    }

    public ListHouseResponse getHousesByManagerRegion(UUID managerId) {
        GetHouseByUserRequest req = GetHouseByUserRequest.newBuilder()
                .setUserId(managerId.toString()).build();
        return houseStub.getHousesByManagerRegion(req);
    }

    public TenantResponse getTenantByUserId(UUID userId) {
        GetTenantByUserIdRequest req = GetTenantByUserIdRequest.newBuilder().setUserId(userId.toString()).build();
        return tenantStub.getTenantByUserId(req);
    }

    /** Returns UUIDs of houses visible to a MANAGER (houses in regions they manage). */
    public Set<UUID> getManagedHouseIds(UUID managerId) {
        return getHousesByManagerRegion(managerId).getHouseList().stream()
                .map(h -> UUID.fromString(h.getId()))
                .collect(Collectors.toSet());
    }
}
