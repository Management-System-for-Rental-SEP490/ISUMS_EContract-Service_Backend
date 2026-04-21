package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isums.contractservice.domains.enums.ContractLanguage;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.PetPolicy;
import com.isums.contractservice.domains.enums.SmokingPolicy;
import com.isums.contractservice.domains.enums.SubleasePolicy;
import com.isums.contractservice.domains.enums.TaxResponsibility;
import com.isums.contractservice.domains.enums.TempResidenceRegisterBy;
import com.isums.contractservice.domains.enums.TenantType;
import com.isums.contractservice.domains.enums.VisitorPolicy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Output DTO for a contract. Renamed all legal fields straight-through from
 * the entity so FE can render the detail page without a second round-trip.
 * Sensitive S3 keys (cccdFrontKey, passportFrontKey) are omitted — FE should
 * request a presigned URL via {@code GET /{id}/pdf-url} when it needs the file.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EContractDto(
        // Core
        UUID id,
        String documentId,
        String documentNo,
        UUID userId,
        String html,
        String name,
        String snapshotKey,
        UUID houseId,
        UUID regionId,
        Instant startAt,
        Instant endAt,
        EContractStatus status,
        String pdfUrl,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,

        // Tenant identity
        TenantType tenantType,
        ContractLanguage contractLanguage,
        String tenantName,
        String cccdNumber,
        LocalDate dateOfBirth,
        String gender,
        String nationality,
        String occupation,
        String permanentAddress,
        Map<String, String> detailedAddress,

        // Passport + visa (foreign tenants)
        String passportNumber,
        LocalDate passportIssueDate,
        String passportIssuePlace,
        LocalDate passportExpiryDate,
        String visaType,
        LocalDate visaExpiryDate,

        // Verification timestamps (flags for FE — "did tenant upload?")
        Instant cccdVerifiedAt,
        Instant passportVerifiedAt,

        // House legal (area, structure, GCN) come from House-service at render —
        // not stored on contract rows.

        // Money
        Long rentAmount,
        Long depositAmount,
        Integer payDate,
        Integer lateDays,
        Integer latePenaltyPercent,
        Integer depositRefundDays,
        Instant handoverDate,
        Integer renewNoticeDays,

        // Rules
        PetPolicy petPolicy,
        SmokingPolicy smokingPolicy,
        SubleasePolicy subleasePolicy,
        VisitorPolicy visitorPolicy,
        TempResidenceRegisterBy tempResidenceRegisterBy,
        TaxResponsibility taxResponsibility,

        // Handover meters
        Map<String, Object> meterReadingsStart,

        // Flags
        Boolean hasPowerCutClause,

        // Termination
        Instant terminatedAt,
        String terminatedReason,
        UUID terminatedBy
) {

    public EContractDto updatePdfUrl(String newPdfUrl) {
        return new EContractDto(
                id, documentId, documentNo, userId, html, name, snapshotKey,
                houseId, regionId, startAt, endAt, status, newPdfUrl, createdBy, createdAt, updatedAt,
                tenantType, contractLanguage, tenantName, cccdNumber,
                dateOfBirth, gender, nationality, occupation, permanentAddress, detailedAddress,
                passportNumber, passportIssueDate, passportIssuePlace, passportExpiryDate,
                visaType, visaExpiryDate, cccdVerifiedAt, passportVerifiedAt,
                rentAmount, depositAmount, payDate, lateDays, latePenaltyPercent,
                depositRefundDays, handoverDate, renewNoticeDays,
                petPolicy, smokingPolicy, subleasePolicy, visitorPolicy,
                tempResidenceRegisterBy, taxResponsibility, meterReadingsStart,
                hasPowerCutClause, terminatedAt, terminatedReason, terminatedBy);
    }
}
