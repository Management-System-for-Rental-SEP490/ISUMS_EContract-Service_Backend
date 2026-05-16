package com.isums.contractservice.domains.entities;

import com.isums.common.i18n.TranslationMap;
import com.isums.common.i18n.TranslationMapConverter;
import com.isums.contractservice.domains.enums.ContractLanguage;
import com.isums.contractservice.domains.enums.DepositStatus;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.PetPolicy;
import com.isums.contractservice.domains.enums.SmokingPolicy;
import com.isums.contractservice.domains.enums.SubleasePolicy;
import com.isums.contractservice.domains.enums.TaxResponsibility;
import com.isums.contractservice.domains.enums.TempResidenceRegisterBy;
import com.isums.contractservice.domains.enums.TenantType;
import com.isums.contractservice.domains.enums.VisitorPolicy;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "econtracts", indexes = {
        @Index(name = "idx_econtracts_house_status",  columnList = "house_id,status"),
        @Index(name = "idx_econtracts_user",          columnList = "user_id"),
        @Index(name = "idx_econtracts_end_at",        columnList = "end_at,status")
})

@SQLRestriction("deleted_at IS NULL")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EContract implements Serializable {

    @Id @GeneratedValue @UuidGenerator
    private UUID id;

    @Column(name = "document_id", unique = true)
    private String documentId;

    @Column(name = "document_no", unique = true)
    private String documentNo;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String html;

    private String name;

    @Column(name = "name_translations", columnDefinition = "text")
    @Convert(converter = TranslationMapConverter.class)
    private TranslationMap nameTranslations;

    @Column(name = "snapshot_key")
    private String snapshotKey;

    @Column(name = "house_id", nullable = false)
    private UUID houseId;

    @Column(name = "region_id")
    private UUID regionId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "rent_amount")
    private Long rentAmount;

    @Column(name = "deposit_amount")
    private Long depositAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_status", length = 40)
    private DepositStatus depositStatus;

    @Column(name = "deposit_due_at")
    private Instant depositDueAt;

    @Column(name = "relocation_source_contract_id")
    private UUID relocationSourceContractId;

    @Column(name = "replaced_by_contract_id")
    private UUID replacedByContractId;

    @Column(name = "transferred_deposit_amount")
    private Long transferredDepositAmount;

    @Column(name = "pay_date")
    private Integer payDate;

    @Column(name = "late_days")
    private Integer lateDays;

    @Column(name = "late_penalty_percent")
    private Integer latePenaltyPercent;

    @Column(name = "deposit_refund_days")
    private Integer depositRefundDays;

    @Column(name = "handover_date")
    private Instant handoverDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "tenant_type", length = 32)
    private TenantType tenantType;

    @Column(name = "cccd_number")
    private String cccdNumber;

    @Column(name = "cccd_front_key")
    private String cccdFrontKey;

    @Column(name = "cccd_back_key")
    private String cccdBackKey;

    @Column(name = "passport_number", length = 64)
    private String passportNumber;

    @Column(name = "passport_issue_date")
    private LocalDate passportIssueDate;

    @Column(name = "passport_issue_place")
    private String passportIssuePlace;

    @Column(name = "passport_expiry_date")
    private LocalDate passportExpiryDate;

    @Column(name = "visa_type", length = 64)
    private String visaType;

    @Column(name = "visa_expiry_date")
    private LocalDate visaExpiryDate;

    @Column(name = "passport_front_key")
    private String passportFrontKey;

    @Column(name = "passport_verified_at")
    private Instant passportVerifiedAt;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 16)
    private String gender;

    @Column(name = "nationality", length = 64)
    private String nationality;

    @Column(name = "occupation")
    private String occupation;

    @Column(name = "permanent_address", length = 512)
    private String permanentAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detailed_address", columnDefinition = "jsonb")
    private Map<String, String> detailedAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "pet_policy", length = 32)
    private PetPolicy petPolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "smoking_policy", length = 32)
    private SmokingPolicy smokingPolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "sublease_policy", length = 32)
    private SubleasePolicy subleasePolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "visitor_policy", length = 32)
    private VisitorPolicy visitorPolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "temp_residence_register_by", length = 32)
    private TempResidenceRegisterBy tempResidenceRegisterBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_responsibility", length = 32)
    private TaxResponsibility taxResponsibility;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meter_readings_start", columnDefinition = "jsonb")
    private Map<String, Object> meterReadingsStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_language", length = 16)
    private ContractLanguage contractLanguage;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "terminated_reason")
    private String terminatedReason;

    @Column(name = "terminated_by")
    private UUID terminatedBy;

    @Column(name = "termination_requested_at")
    private Instant terminationRequestedAt;

    @Column(name = "renew_notice_days")
    private Integer renewNoticeDays;

    @Column(name = "has_power_cut_clause")
    private Boolean hasPowerCutClause = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EContractStatus status;

    @Column(name = "tenant_name")
    private String tenantName;

    @Column(name = "tenant_email")
    private String tenantEmail;

    @Column(name = "cccd_verified_at")
    private Instant cccdVerifiedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "bookable_window_notified_at")
    private Instant bookableWindowNotifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;
}
