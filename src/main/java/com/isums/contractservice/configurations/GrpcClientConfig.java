package com.isums.contractservice.configurations;

import com.isums.contractservice.grpc.AssetGrpcServiceGrpc;
import com.isums.contractservice.grpc.HouseGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    @Bean
    HouseGrpc.HouseBlockingStub houseStub(GrpcChannelFactory channels, GrpcTokenInterceptor tokenInterceptor) {
        return HouseGrpc.newBlockingStub(channels.createChannel("house"))
                .withInterceptors(tokenInterceptor);
    }

    @Bean
    AssetGrpcServiceGrpc.AssetGrpcServiceBlockingStub assetStub(GrpcChannelFactory channels, GrpcTokenInterceptor tokenInterceptor) {
        return AssetGrpcServiceGrpc.newBlockingStub(channels.createChannel("asset"))
                .withInterceptors(tokenInterceptor);
    }
}
