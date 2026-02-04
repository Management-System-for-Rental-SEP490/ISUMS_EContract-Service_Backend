package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreateRentalDraftRequest(
        @NotBlank String landlordName,
        @NotBlank String landlordId,
        String landlordIdIssue,
        @NotBlank String landlordAddress,
        String landlordPhone,
        String landlordEmail,
        String landlordBank,

        @NotBlank String tenantName,
        @NotBlank String tenantId,
        String tenantIdIssue,
        @NotBlank String tenantAddress,
        String tenantPhone,
        String tenantEmail,

        @NotBlank String propertyAddress,
        String area,
        String structure,
        String purpose,
        String ownershipDocs,

        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,

        @NotNull BigDecimal rentAmount,
        String taxFeeNote,
        String payCycle,
        String payDay,
        Integer lateDays,
        BigDecimal latePenaltyPercentPerMonth,

        BigDecimal depositAmount,
        LocalDate depositDate,
        Integer depositRefundDays,

        LocalDate handoverDate,
        String utilityRules,

        Integer renewNoticeDays,
        Integer landlordNoticeDays,
        Integer cureDays,
        Integer maxLateDays,
        String earlyTerminationPenalty,
        String landlordBreachCompensation,
        Integer forceMajeureNoticeHours,
        Integer disputeDays,
        String disputeForum,
        String effectiveDate,
        Integer copies,
        Integer eachKeep,

        String additionalTerm,

        List<RentalAssetItem> assets
) {
    public record RentalAssetItem(String item, String qty, String condition, String note) {}
}

