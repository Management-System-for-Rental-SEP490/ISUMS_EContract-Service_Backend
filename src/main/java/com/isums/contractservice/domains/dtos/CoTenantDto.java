package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.CoTenantIdentityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

@Builder
public record CoTenantDto(
        @NotBlank String fullName,

        @NotBlank
        @Pattern(regexp = "^[A-Z0-9]{6,20}$",
                message = "Số giấy tờ không hợp lệ — chấp nhận 6–20 ký tự chữ và số")
        String identityNumber,

        @NotNull CoTenantIdentityType identityType,

        @Nullable LocalDate dateOfBirth,
        @Nullable String gender,
        @Nullable String nationality,

        @NotBlank String relationship,

        @Nullable
        @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Số điện thoại không hợp lệ")
        String phoneNumber
) {
}
