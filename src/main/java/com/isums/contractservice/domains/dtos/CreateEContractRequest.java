package com.isums.contractservice.domains.dtos;

import jakarta.validation.constraints.*;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record CreateEContractRequest(
        @NotNull Boolean isNewAccount,
        @NotBlank String name,
        @NotBlank String email,
        @Nullable String phoneNumber,
        @Nullable String identityNumber,
        @Nullable Instant dateOfIssue,
        @Nullable String placeOfIssue,
        @Nullable String tenantAddress,

        @NotNull UUID houseId,
        @NotNull Instant startDate,
        @NotNull Instant endDate,

        @NotNull @Min(0) Long rentAmount,
        @NotNull @Min(1) @Max(31) Integer payDate,
        @NotNull @Min(0) Long depositAmount,
        @NotNull Instant depositDate,
        @NotNull Instant handoverDate,

        @Nullable Integer lateDays,
        @Nullable Integer latePenaltyPercent,
        @Nullable Integer depositRefundDays,
        @Nullable String payCycle,
        @Nullable String taxFeeNote,
        @Nullable String purpose,
        @Nullable String area,
        @Nullable String structure,
        @Nullable String ownershipDocs,
        @Nullable Integer renewNoticeDays,
        @Nullable Integer landlordNoticeDays,
        @Nullable Integer cureDays,
        @Nullable Integer maxLateDays,
        @Nullable String earlyTerminationPenalty,
        @Nullable String landlordBreachCompensation,
        @Nullable Integer forceMajeureNoticeHours,
        @Nullable Integer disputeDays,
        @Nullable String disputeForum,
        @Nullable Integer copies,
        @Nullable Integer eachKeep
) {
    public int lateDaysOrDefault() {
        return lateDays != null ? lateDays : 3;
    }

    public int latePenaltyPercentOrDefault() {
        return latePenaltyPercent != null ? latePenaltyPercent : 5;
    }

    public int depositRefundDaysOrDefault() {
        return depositRefundDays != null ? depositRefundDays : 30;
    }

    public String payCycleOrDefault() {
        return payCycle != null ? payCycle : "Hàng tháng";
    }

    public String taxFeeNoteOrDefault() {
        return taxFeeNote != null ? taxFeeNote : "Miễn thuế";
    }

    public String purposeOrDefault() {
        return purpose != null ? purpose : "Thuê để ở";
    }

    public String areaOrDefault() {
        return area != null ? area : "Theo thực tế";
    }

    public String structureOrDefault() {
        return structure != null ? structure : "Theo thực tế";
    }

    public String ownershipDocsOrDefault() {
        return ownershipDocs != null ? ownershipDocs : "Theo hồ sơ nhà";
    }

    public int renewNoticeDaysOrDefault() {
        return renewNoticeDays != null ? renewNoticeDays : 30;
    }

    public int landlordNoticeDaysOrDefault() {
        return landlordNoticeDays != null ? landlordNoticeDays : 30;
    }

    public int cureDaysOrDefault() {
        return cureDays != null ? cureDays : 7;
    }

    public int maxLateDaysOrDefault() {
        return maxLateDays != null ? maxLateDays : 3;
    }

    public String earlyTerminationPenaltyOrDefault() {
        return earlyTerminationPenalty != null ? earlyTerminationPenalty : "Mất toàn bộ tiền cọc";
    }

    public String landlordBreachCompensationOrDefault() {
        return landlordBreachCompensation != null ? landlordBreachCompensation : "Đền cọc gấp đôi";
    }

    public int forceMajeureNoticeHoursOrDefault() {
        return forceMajeureNoticeHours != null ? forceMajeureNoticeHours : 24;
    }

    public int disputeDaysOrDefault() {
        return disputeDays != null ? disputeDays : 30;
    }

    public String disputeForumOrDefault() {
        return disputeForum != null ? disputeForum : "Tòa án nhân dân có thẩm quyền";
    }

    public int copiesOrDefault() {
        return copies != null ? copies : 2;
    }

    public int eachKeepOrDefault() {
        return eachKeep != null ? eachKeep : 1;
    }
}