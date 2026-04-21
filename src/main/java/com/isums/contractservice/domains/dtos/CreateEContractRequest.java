package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.ContractLanguage;
import com.isums.contractservice.domains.enums.PetPolicy;
import com.isums.contractservice.domains.enums.SmokingPolicy;
import com.isums.contractservice.domains.enums.SubleasePolicy;
import com.isums.contractservice.domains.enums.TaxResponsibility;
import com.isums.contractservice.domains.enums.TempResidenceRegisterBy;
import com.isums.contractservice.domains.enums.TenantType;
import com.isums.contractservice.domains.enums.VisitorPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Builder
public record CreateEContractRequest(
        @NotNull Boolean isNewAccount,
        @NotBlank String name,
        @NotBlank @Email String email,
        @Nullable
        @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Số điện thoại không hợp lệ")
        String phoneNumber,

        // Tenant type drives which identity doc is required.
        @Nullable TenantType tenantType,

        // VN CCCD — 12 digits. Required when tenantType = VIETNAMESE.
        @Nullable
        @Pattern(regexp = "^\\d{12}$", message = "CCCD phải có đúng 12 chữ số")
        String identityNumber,
        @Nullable Instant dateOfIssue,
        @Nullable String placeOfIssue,
        @Nullable String tenantAddress,
        @Nullable Boolean hasPowerCutClause,

        // Passport — ICAO 9303 format. Required when tenantType = FOREIGNER.
        @Nullable
        @Pattern(regexp = "^[A-Z0-9]{6,9}$", message = "Số hộ chiếu không đúng định dạng ICAO")
        String passportNumber,
        @Nullable LocalDate passportIssueDate,
        @Nullable String passportIssuePlace,
        @Nullable LocalDate passportExpiryDate,
        @Nullable String visaType,
        @Nullable LocalDate visaExpiryDate,
        @Nullable String nationality,

        // Tenant personal info (Luật Cư trú 2020)
        @Nullable @Past LocalDate dateOfBirth,
        @Nullable String gender,
        @Nullable String occupation,
        @Nullable String permanentAddress,
        @Nullable @Valid DetailedAddressDto detailedAddress,

        // Co-tenants
        @Nullable @Valid List<CoTenantDto> coTenants,

        // House
        @NotNull UUID houseId,
        @NotNull Instant startDate,
        @NotNull Instant endDate,

        // House facts (area, structure, GCN) come from House-service gRPC —
        // one fact per house, used by every lease contract. Not on the request.

        // Money
        @NotNull @Min(0) Long rentAmount,
        @NotNull @Min(1) @Max(31) Integer payDate,
        @NotNull @Min(0) Long depositAmount,
        @NotNull Instant depositDate,
        @NotNull Instant handoverDate,

        @Nullable @Valid MeterReadingsDto meterReadingsStart,

        @Nullable Integer lateDays,
        @Nullable Integer latePenaltyPercent,
        @Nullable Integer depositRefundDays,
        @Nullable String payCycle,
        @Nullable String taxFeeNote,
        @Nullable Integer renewNoticeDays,
        @Nullable Integer landlordNoticeDays,
        @Nullable Integer cureDays,
        @Nullable Integer maxLateDays,
        @Nullable String earlyTerminationPenalty,
        @Nullable String landlordBreachCompensation,
        @Nullable Integer forceMajeureNoticeHours,
        @Nullable Integer disputeDays,
        @Nullable String disputeForum,

        // Rules (nội quy)
        @Nullable PetPolicy petPolicy,
        @Nullable SmokingPolicy smokingPolicy,
        @Nullable SubleasePolicy subleasePolicy,
        @Nullable VisitorPolicy visitorPolicy,

        // Legal responsibilities
        @Nullable TempResidenceRegisterBy tempResidenceRegisterBy,
        @Nullable TaxResponsibility taxResponsibility,

        // Language selection
        @Nullable ContractLanguage contractLanguage
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

    public TenantType tenantTypeOrDefault() {
        return tenantType != null ? tenantType : TenantType.VIETNAMESE;
    }

    public ContractLanguage contractLanguageOrDefault() {
        if (contractLanguage != null) return contractLanguage;
        return tenantTypeOrDefault() == TenantType.FOREIGNER
                ? ContractLanguage.VI_EN
                : ContractLanguage.VI;
    }

    public PetPolicy petPolicyOrDefault() {
        return petPolicy != null ? petPolicy : PetPolicy.ALLOWED_WITH_APPROVAL;
    }

    public SmokingPolicy smokingPolicyOrDefault() {
        return smokingPolicy != null ? smokingPolicy : SmokingPolicy.OUTDOOR_ONLY;
    }

    public SubleasePolicy subleasePolicyOrDefault() {
        return subleasePolicy != null ? subleasePolicy : SubleasePolicy.NOT_ALLOWED;
    }

    public VisitorPolicy visitorPolicyOrDefault() {
        return visitorPolicy != null ? visitorPolicy : VisitorPolicy.UNRESTRICTED;
    }

    public TempResidenceRegisterBy tempResidenceRegisterByOrDefault() {
        return tempResidenceRegisterBy != null ? tempResidenceRegisterBy : TempResidenceRegisterBy.LANDLORD;
    }

    public TaxResponsibility taxResponsibilityOrDefault() {
        return taxResponsibility != null ? taxResponsibility : TaxResponsibility.LANDLORD;
    }

    /**
     * True when caller provided enough identity fields for the declared tenant type.
     * Vietnamese: needs CCCD. Foreigner: needs passport + nationality.
     * Enforced at service layer because cross-field rules don't fit Jakarta annotations.
     */
    public boolean hasRequiredIdentity() {
        TenantType t = tenantTypeOrDefault();
        if (t == TenantType.VIETNAMESE) {
            return identityNumber != null && !identityNumber.isBlank();
        }
        return passportNumber != null && !passportNumber.isBlank()
                && nationality != null && !nationality.isBlank();
    }
}
