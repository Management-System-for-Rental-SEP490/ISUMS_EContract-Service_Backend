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
                message = "Invalid identity number — accepts 6–20 alphanumeric characters")
        String identityNumber,

        @NotNull CoTenantIdentityType identityType,

        @Nullable LocalDate dateOfBirth,
        @Nullable String gender,
        @Nullable String nationality,

        @NotBlank String relationship,

        @Nullable
        @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Invalid phone number")
        String phoneNumber,

        @Nullable String passportNumber,
        @Nullable String visaType,
        @Nullable LocalDate visaExpiryDate
) {
}

