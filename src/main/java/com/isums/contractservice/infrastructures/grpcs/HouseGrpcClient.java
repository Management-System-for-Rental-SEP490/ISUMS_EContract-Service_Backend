package com.isums.contractservice.infrastructures.grpcs;

import com.isums.houseservice.grpc.GetHouseRequest;
import com.isums.houseservice.grpc.HouseResponse;
import com.isums.houseservice.grpc.HouseServiceGrpc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HouseGrpcClient {

    private final HouseServiceGrpc.HouseServiceBlockingStub houseStub;

    public HouseResponse getHouseById(UUID id) {
        GetHouseRequest req = GetHouseRequest.newBuilder().setHouseId(id.toString()).build();
        return houseStub.getHouseById(req);
    }

}
