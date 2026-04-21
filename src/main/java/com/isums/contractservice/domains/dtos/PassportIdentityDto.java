package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Passport OCR output based on ICAO 9303 TD3 machine-readable zone (MRZ).
 * issueDate / issuePlace are visual-zone fields — the MRZ itself does not
 * carry them, so they may be null if PaddleOCR could not read the VIZ.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PassportIdentityDto {

    private String passportNumber;
    private String fullName;
    private String givenName;
    private String surname;
    private String dateOfBirth;
    private String gender;
    private String nationality;
    private String countryCode;
    private String issueDate;
    private String issuePlace;
    private String expiryDate;
    private String mrz;
    private String frontImageKey;
}
