package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.CashDepositReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface CashDepositReceiptRepository extends JpaRepository<CashDepositReceipt, UUID> {

    Optional<CashDepositReceipt> findByIdempotencyKey(String idempotencyKey);

    Optional<CashDepositReceipt> findByReceiptNumber(String receiptNumber);

    @Query("""
            SELECT r FROM CashDepositReceipt r
            WHERE r.contractId = :contractId
              AND r.voidedAt IS NULL
            """)
    Optional<CashDepositReceipt> findActiveByContractId(UUID contractId);

    @Query(value = "SELECT nextval('cash_deposit_receipt_seq')", nativeQuery = true)
    Long nextReceiptSequence();
}
