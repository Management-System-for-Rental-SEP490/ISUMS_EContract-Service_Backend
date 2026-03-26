package com.isums.contractservice.domains.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.isums.contractservice.domains.entities.LandlordProfile;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LandlordProfileDto {
    UUID id;
    UUID userId;
    String fullName;
    String identityNumber;
    String identityIssueDate;
    String identityIssuePlace;
    String address;
    String phoneNumber;
    String email;
    String bankAccount;

    public static LandlordProfileDto from(LandlordProfile p) {
        return LandlordProfileDto.builder()
                .id(p.getId())
                .userId(p.getUserId())
                .fullName(p.getFullName())
                .identityNumber(p.getIdentityNumber())
                .identityIssueDate(p.getIdentityIssueDate())
                .identityIssuePlace(p.getIdentityIssuePlace())
                .address(p.getAddress())
                .phoneNumber(p.getPhoneNumber())
                .email(p.getEmail())
                .bankAccount(p.getBankAccount())
                .build();
    }
}