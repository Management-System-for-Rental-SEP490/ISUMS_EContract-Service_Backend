package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantIdentityDto {

    private String identityNumber;
    private String fullName;
    private String dateOfBirth;
    private String gender;
    private String address;
    private String issueDate;
    private String issuePlace;
    private String frontImageKey;
    private String backImageKey;
}