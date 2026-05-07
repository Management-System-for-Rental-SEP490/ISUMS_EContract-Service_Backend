package com.isums.contractservice.domains.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cash_deposit_receipt", indexes = {
        @Index(name = "idx_cash_deposit_receipt_confirmed_by",
               columnList = "confirmed_by_user_id, created_at DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashDepositReceipt {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "receipt_number", nullable = false, unique = true, length = 32)
    private String receiptNumber;

    @Column(nullable = false)
    private long amount;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    @Column(name = "confirmed_by_user_id", nullable = false)
    private UUID confirmedByUserId;

    @Column(name = "payer_name", length = 255)
    private String payerName;

    @Column(name = "payee_name", length = 255)
    private String payeeName;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by_user_id")
    private UUID voidedByUserId;

    @Column(name = "void_reason", columnDefinition = "text")
    private String voidReason;

    @Column(name = "voids_receipt_id")
    private UUID voidsReceiptId;

    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
