package com.isums.contractservice.infrastructures.grpcs;

import com.isums.contractservice.grpc.GetHouseRequest;
import com.isums.contractservice.grpc.HouseGrpc;
import com.isums.contractservice.grpc.HouseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HouseGrpcClient {

    private final HouseGrpc.HouseBlockingStub houseStub;

    public HouseResponse getHouseById(UUID id) {
        GetHouseRequest req = GetHouseRequest.newBuilder().setHouseId(id.toString()).build();
        return houseStub.getHouseById(req);
    }

}
