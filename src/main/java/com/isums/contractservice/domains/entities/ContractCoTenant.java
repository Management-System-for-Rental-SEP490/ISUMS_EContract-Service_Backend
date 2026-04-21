package com.isums.contractservice.domains.entities;

import com.isums.contractservice.domains.enums.CoTenantIdentityType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "contract_co_tenants", indexes = {
        @Index(name = "idx_contract_co_tenants_contract", columnList = "contract_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractCoTenant {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "identity_number", nullable = false, length = 64)
    private String identityNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 32)
    private CoTenantIdentityType identityType;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 16)
    private String gender;

    @Column(name = "nationality", length = 64)
    private String nationality;

    @Column(name = "relationship", nullable = false, length = 64)
    private String relationship;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
