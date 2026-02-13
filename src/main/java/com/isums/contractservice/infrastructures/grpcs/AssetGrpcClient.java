package com.isums.contractservice.infrastructures.grpcs;

import com.isums.contractservice.grpc.AssetGrpcServiceGrpc;
import com.isums.contractservice.grpc.AssetItemDto;
import com.isums.contractservice.grpc.GetAssetItemsByHouseIdRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssetGrpcClient {

    private final AssetGrpcServiceGrpc.AssetGrpcServiceBlockingStub assetStub;

    public List<AssetItemDto> getAssetItemsByHouseId(UUID houseId) {
        var req = GetAssetItemsByHouseIdRequest.newBuilder().setHouseId(String.valueOf(houseId)).build();

        var res = assetStub.getAssetItemsByHouseId(req);
        return res.getAssetItemsList();
    }
}
