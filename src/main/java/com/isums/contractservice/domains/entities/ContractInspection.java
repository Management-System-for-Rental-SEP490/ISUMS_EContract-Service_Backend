package com.isums.contractservice.domains.entities;

import com.isums.contractservice.domains.enums.ContractInspectionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "contract_inspections")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ContractInspection {

    @Id @GeneratedValue @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID contractId;

    @Column(nullable = false)
    private UUID houseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractInspectionStatus status;

    private UUID inspectorStaffId;
    private String notes;
    private Long deductionAmount;

    @ElementCollection
    @CollectionTable(
            name = "contract_inspection_photos",
            joinColumns = @JoinColumn(name = "inspection_id")
    )
    @Column(name = "photo_url", nullable = false)
    private List<String> photoUrls = new ArrayList<>();

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant completedAt;
}