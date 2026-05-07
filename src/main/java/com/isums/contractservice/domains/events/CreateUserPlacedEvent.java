package com.isums.contractservice.domains.events;

import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateUserPlacedEvent {
    @Column(nullable = false)
    private UUID id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String email;
    private String phoneNumber;
    private String identityNumber;
    private Boolean isEnabled;

    private String dateOfIssue;
    private String placeOfIssue;
    private String permanentAddress;

    private String dateOfBirth;
    private String gender;

    private String passportNumber;
    private String passportIssueDate;
    private String passportExpiryDate;
    private String nationality;
    private String visaType;
    private String visaExpiryDate;
}

