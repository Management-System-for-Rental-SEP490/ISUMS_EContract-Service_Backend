package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.LandlordProfileDto;
import com.isums.contractservice.domains.dtos.UpsertLandlordProfileRequest;
import com.isums.contractservice.domains.entities.LandlordProfile;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.repositories.LandlordProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LandlordProfileServiceImpl")
class LandlordProfileServiceImplTest {

    @Mock private LandlordProfileRepository repository;

    @InjectMocks private LandlordProfileServiceImpl service;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    private UpsertLandlordProfileRequest req() {
        return new UpsertLandlordProfileRequest(
                "Nguyen Van A", "0123456789", "2020-01-01", "Ha Noi",
                "1 Pho Hue", "0900", "a@b.com", "123456789");
    }

    @Nested
    @DisplayName("upsert")
    class Upsert {

        @Test
        @DisplayName("creates new profile when not exists")
        void create() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());

            LandlordProfileDto dto = service.upsert(userId, req());

            assertThat(dto.getFullName()).isEqualTo("Nguyen Van A");
            assertThat(dto.getUserId()).isEqualTo(userId);

            ArgumentCaptor<LandlordProfile> cap = ArgumentCaptor.forClass(LandlordProfile.class);
            verify(repository).save(cap.capture());
            LandlordProfile saved = cap.getValue();
            assertThat(saved.getUserId()).isEqualTo(userId);
            assertThat(saved.getIdentityNumber()).isEqualTo("0123456789");
            assertThat(saved.getEmail()).isEqualTo("a@b.com");
        }

        @Test
        @DisplayName("updates existing profile when already exists")
        void update() {
            LandlordProfile existing = LandlordProfile.builder()
                    .id(UUID.randomUUID()).userId(userId)
                    .fullName("Old Name").identityNumber("OLD").build();
            when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));

            service.upsert(userId, req());

            ArgumentCaptor<LandlordProfile> cap = ArgumentCaptor.forClass(LandlordProfile.class);
            verify(repository).save(cap.capture());
            LandlordProfile saved = cap.getValue();
            assertThat(saved.getId()).isEqualTo(existing.getId());
            assertThat(saved.getFullName()).isEqualTo("Nguyen Van A");
            assertThat(saved.getIdentityNumber()).isEqualTo("0123456789");
        }
    }

    @Nested
    @DisplayName("getByUserId")
    class GetByUserId {

        @Test
        @DisplayName("returns dto when profile exists")
        void returnsDto() {
            LandlordProfile profile = LandlordProfile.builder()
                    .id(UUID.randomUUID()).userId(userId)
                    .fullName("Nguyen Van A").identityNumber("0123456789")
                    .email("a@b.com").build();
            when(repository.findByUserId(userId)).thenReturn(Optional.of(profile));

            LandlordProfileDto dto = service.getByUserId(userId);
            assertThat(dto.getFullName()).isEqualTo("Nguyen Van A");
        }

        @Test
        @DisplayName("throws NotFoundException when profile missing")
        void missing() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getByUserId(userId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Landlord profile not found");
        }
    }
}
