package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.ContractLanguage;
import com.isums.contractservice.domains.enums.PetPolicy;
import com.isums.contractservice.domains.enums.SmokingPolicy;
import com.isums.contractservice.domains.enums.SubleasePolicy;
import com.isums.contractservice.domains.enums.TaxResponsibility;
import com.isums.contractservice.domains.enums.TempResidenceRegisterBy;
import com.isums.contractservice.domains.enums.VisitorPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Partial-update contract DTO used when the contract is in CORRECTING state.
 * All fields nullable — only non-null fields are applied (MapStruct
 * {@code NullValuePropertyMappingStrategy.IGNORE}).
 *
 * Immutable fields after draft (not in this DTO by design):
 * - houseId, userId, createdBy, status (state machine only)
 * - startDate, endDate (contract period is contractual, use renewal flow instead)
 * - tenantType (changing from VN→foreign mid-correction would break identity docs already uploaded)
 */
public record UpdateEContractRequest(
        @Nullable String html,
        @Nullable String name,

        // Money (landlord may correct after tenant pushback)
        @Nullable @Min(0) Long rentAmount,
        @Nullable @Min(0) Long depositAmount,
        @Nullable @Min(1) @Max(31) Integer payDate,
        @Nullable @Min(0) Integer lateDays,
        @Nullable @Min(0) @Max(100) Integer latePenaltyPercent,
        @Nullable @Min(0) Integer depositRefundDays,
        @Nullable @Min(0) Integer renewNoticeDays,
        @Nullable Instant handoverDate,

        // Tenant personal (typos, missing fields)
        @Nullable String tenantName,
        @Nullable
        @Pattern(regexp = "^\\d{12}$", message = "CCCD phải có đúng 12 chữ số")
        String cccdNumber,
        @Nullable LocalDate dateOfBirth,
        @Nullable String gender,
        @Nullable String occupation,
        @Nullable String permanentAddress,
        @Nullable @Valid DetailedAddressDto detailedAddress,

        // Passport + visa
        @Nullable
        @Pattern(regexp = "^[A-Z0-9]{6,9}$", message = "Số hộ chiếu không đúng định dạng ICAO")
        String passportNumber,
        @Nullable LocalDate passportIssueDate,
        @Nullable String passportIssuePlace,
        @Nullable LocalDate passportExpiryDate,
        @Nullable String visaType,
        @Nullable LocalDate visaExpiryDate,
        @Nullable String nationality,

        // House legal
        // Rules
        @Nullable PetPolicy petPolicy,
        @Nullable SmokingPolicy smokingPolicy,
        @Nullable SubleasePolicy subleasePolicy,
        @Nullable VisitorPolicy visitorPolicy,
        @Nullable TempResidenceRegisterBy tempResidenceRegisterBy,
        @Nullable TaxResponsibility taxResponsibility,

        // Handover meters
        @Nullable @Valid MeterReadingsDto meterReadingsStart,

        // Language (landlord may flip to bilingual after draft)
        @Nullable ContractLanguage contractLanguage,

        // Flags
        @Nullable Boolean hasPowerCutClause
) {
}
