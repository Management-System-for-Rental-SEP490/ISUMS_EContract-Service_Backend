package com.isums.contractservice.infrastructures.grpcs;

import com.isums.contractservice.domains.dtos.ContractPaymentStatus;
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
        return getInvoiceStatus(houseId, tenantId).depositPaid();
    }

    public ContractPaymentStatus getInvoiceStatus(UUID houseId, UUID tenantId) {
        if (houseId == null || tenantId == null) {
            return ContractPaymentStatus.unavailable();
        }
        InvoiceStatusResponse response = stub.getInvoiceStatus(InvoiceStatusRequest.newBuilder()
                .setHouseId(houseId.toString())
                .setTenantId(tenantId.toString())
                .build());
        return new ContractPaymentStatus(
                response.getDepositPaid(),
                response.getFirstRentPaid(),
                response.getPendingInvoiceId().isBlank()
                        ? null
                        : UUID.fromString(response.getPendingInvoiceId()));
    }
}
