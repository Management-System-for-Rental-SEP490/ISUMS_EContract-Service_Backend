package com.isums.contractservice.infrastructures.grpcs;

import com.isums.paymentservice.grpc.InvoiceStatusRequest;
import com.isums.paymentservice.grpc.InvoiceStatusResponse;
import com.isums.paymentservice.grpc.PaymentServiceGrpc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentGrpcClient {

    private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    public boolean isDepositPaid(UUID houseId, UUID tenantId) {
        if (houseId == null || tenantId == null) {
            return false;
        }
        InvoiceStatusResponse response = stub.getInvoiceStatus(InvoiceStatusRequest.newBuilder()
                .setHouseId(houseId.toString())
                .setTenantId(tenantId.toString())
                .build());
        return response.getDepositPaid();
    }
}
