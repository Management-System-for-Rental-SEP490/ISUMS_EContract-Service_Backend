package com.isums.contractservice.domains.entities;

import com.isums.contractservice.domains.enums.EContractStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "econtracts", indexes = {
        @Index(name = "idx_econtracts_house_status", columnList = "house_id,status"),
        @Index(name = "idx_econtracts_tenant", columnList = "tenant_id"),
        @Index(name = "idx_econtracts_user", columnList = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EContract implements Serializable {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    // document form vnpt
    @Column(name = "document_id", unique = true)
    private String documentId;

    @Column(name = "document_no", unique = true)
    private String documentNo;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, columnDefinition = "text")
    private String html;

    private String name;

    @Column(name = "snapshot_key")
    private String snapshotKey;

    @Column(name = "house_id", nullable = false)
    private UUID houseId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    private Long price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EContractStatus status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;
}
