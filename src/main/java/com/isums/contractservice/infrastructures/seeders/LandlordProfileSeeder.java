package com.isums.contractservice.infrastructures.seeders;

import com.isums.contractservice.domains.entities.LandlordProfile;
import com.isums.contractservice.infrastructures.repositories.LandlordProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class LandlordProfileSeeder implements CommandLineRunner {

    private final LandlordProfileRepository landlordProfileRepository;

    private static final UUID LANDLORD_USER_ID = UUID.fromString("31d2a893-9d58-45a0-b1a8-36b3f4245d8c");

    @Override
    public void run(String @NonNull ... args) {
        if (landlordProfileRepository.findByUserId(LANDLORD_USER_ID).isPresent()) {
            log.info("[Seeder] LandlordProfile already exists, skipping");
            return;
        }

        LandlordProfile profile = LandlordProfile.builder()
                .userId(LANDLORD_USER_ID)
                .fullName("Trần Đức Hiệu")
                .identityNumber("051204005810")
                .identityIssueDate("01/01/2023")
                .identityIssuePlace("Cục Cảnh sát quản lý hành chính về trật tự xã hội")
                .address("Đức Phú, Mộ Đức, Quảng Ngãi")
                .phoneNumber("0326336224")
                .email("hoangtuzami@gmail.com")
                .bankAccount("0326336224 (TPBank)")
                .build();

        landlordProfileRepository.save(profile);
        log.info("[Seeder] LandlordProfile created for userId={}", LANDLORD_USER_ID);
    }
}