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

public record UpdateEContractRequest(
        @Nullable String html,
        @Nullable String name,

        @Nullable @Min(0) Long rentAmount,
        @Nullable @Min(0) Long depositAmount,
        @Nullable @Min(1) @Max(31) Integer payDate,
        @Nullable @Min(0) Integer lateDays,
        @Nullable @Min(0) @Max(100) Integer latePenaltyPercent,
        @Nullable @Min(0) Integer depositRefundDays,
        @Nullable @Min(0) Integer renewNoticeDays,
        @Nullable Instant handoverDate,

        @Nullable String tenantName,
        @Nullable
        @Pattern(regexp = "^\\d{12}$", message = "Citizen ID must have exactly 12 digits")
        String cccdNumber,
        @Nullable LocalDate dateOfBirth,
        @Nullable String gender,
        @Nullable String occupation,
        @Nullable String permanentAddress,
        @Nullable @Valid DetailedAddressDto detailedAddress,

        @Nullable
        @Pattern(regexp = "^[A-Z0-9]{6,9}$", message = "Passport number does not match ICAO format")
        String passportNumber,
        @Nullable LocalDate passportIssueDate,
        @Nullable String passportIssuePlace,
        @Nullable LocalDate passportExpiryDate,
        @Nullable String visaType,
        @Nullable LocalDate visaExpiryDate,
        @Nullable String nationality,

        @Nullable PetPolicy petPolicy,
        @Nullable SmokingPolicy smokingPolicy,
        @Nullable SubleasePolicy subleasePolicy,
        @Nullable VisitorPolicy visitorPolicy,
        @Nullable TempResidenceRegisterBy tempResidenceRegisterBy,
        @Nullable TaxResponsibility taxResponsibility,

        @Nullable @Valid MeterReadingsDto meterReadingsStart,

        @Nullable ContractLanguage contractLanguage,

        @Nullable Boolean hasPowerCutClause
) {
}

