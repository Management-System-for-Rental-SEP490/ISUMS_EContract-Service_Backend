package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.CreateLegalTemplateRequest;
import com.isums.contractservice.domains.dtos.LegalTemplateDto;
import com.isums.contractservice.domains.entities.LegalTemplate;
import com.isums.contractservice.domains.enums.ContractLanguage;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.repositories.LegalTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LegalTemplateServiceImpl")
class LegalTemplateServiceImplTest {

    @Mock private LegalTemplateRepository repo;
    @InjectMocks private LegalTemplateServiceImpl service;

    private static final String KEY_LANDLORD_FAULT = "RELOCATION_LANDLORD_FAULT_BASIS";
    private static final String KEY_ACTIVE_LEASE = "RELOCATION_ACTIVE_LEASE_UPGRADE_BASIS";

    private LegalTemplate row(String key, String lang, String text, Instant effectiveAt) {
        LegalTemplate t = LegalTemplate.builder()
                .id(UUID.randomUUID())
                .templateKey(key)
                .lang(lang)
                .text(text)
                .effectiveAt(effectiveAt != null ? effectiveAt : Instant.now())
                .createdBy(UUID.randomUUID())
                .build();
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        return t;
    }

    @Nested
    @DisplayName("resolveSnapshot")
    class ResolveSnapshot {

        @Test
        @DisplayName("rejects unknown template key")
        void unknownKey() {
            assertThatThrownBy(() ->
                    service.resolveSnapshot("NOT_A_REAL_KEY", ContractLanguage.VI))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown legal template key");
        }

        @Test
        @DisplayName("VI contract returns VI-only text")
        void viContract() {
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("vi"), any(Instant.class), any(Pageable.class)))
                    .thenReturn(List.of(row(KEY_LANDLORD_FAULT, "vi", "VN text", null)));

            String out = service.resolveSnapshot(KEY_LANDLORD_FAULT, ContractLanguage.VI);

            assertThat(out).isEqualTo("VN text");
            // only VI lookup happens
            verify(repo, never()).findActiveAt(anyString(), eq("en"), any(), any());
        }

        @Test
        @DisplayName("null contract language defaults to VI-only")
        void nullLang() {
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("vi"), any(), any()))
                    .thenReturn(List.of(row(KEY_LANDLORD_FAULT, "vi", "VN", null)));

            assertThat(service.resolveSnapshot(KEY_LANDLORD_FAULT, null)).isEqualTo("VN");
        }

        @Test
        @DisplayName("VI_EN contract returns VI + separator + EN")
        void viEnBilingual() {
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("vi"), any(), any()))
                    .thenReturn(List.of(row(KEY_LANDLORD_FAULT, "vi", "VN", null)));
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("en"), any(), any()))
                    .thenReturn(List.of(row(KEY_LANDLORD_FAULT, "en", "English", null)));

            String out = service.resolveSnapshot(KEY_LANDLORD_FAULT, ContractLanguage.VI_EN);

            assertThat(out).contains("VN").contains("English");
            assertThat(out.indexOf("VN")).isLessThan(out.indexOf("English")); // VI first
        }

        @Test
        @DisplayName("VI_JA contract uses ja secondary lookup")
        void viJaBilingual() {
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("vi"), any(), any()))
                    .thenReturn(List.of(row(KEY_LANDLORD_FAULT, "vi", "VN", null)));
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("ja"), any(), any()))
                    .thenReturn(List.of(row(KEY_LANDLORD_FAULT, "ja", "JA-text", null)));

            String out = service.resolveSnapshot(KEY_LANDLORD_FAULT, ContractLanguage.VI_JA);

            assertThat(out).contains("VN").contains("JA-text");
            verify(repo).findActiveAt(eq(KEY_LANDLORD_FAULT), eq("ja"), any(), any());
        }

        @Test
        @DisplayName("VI_EN with missing EN translation falls back to VI-only with warn (no throw)")
        void missingSecondaryFallsBack() {
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("vi"), any(), any()))
                    .thenReturn(List.of(row(KEY_LANDLORD_FAULT, "vi", "VN-only", null)));
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("en"), any(), any()))
                    .thenReturn(List.of()); // no EN seeded

            String out = service.resolveSnapshot(KEY_LANDLORD_FAULT, ContractLanguage.VI_EN);

            assertThat(out).isEqualTo("VN-only"); // graceful degrade
        }

        @Test
        @DisplayName("missing required VI throws (mis-seeded environment)")
        void missingVi() {
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("vi"), any(), any()))
                    .thenReturn(List.of());

            assertThatThrownBy(() ->
                    service.resolveSnapshot(KEY_LANDLORD_FAULT, ContractLanguage.VI))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Missing required VI legal template");
        }

        @Test
        @DisplayName("repo lookup uses Pageable size 1 (LIMIT 1)")
        void enforcesLimit() {
            when(repo.findActiveAt(eq(KEY_ACTIVE_LEASE), eq("vi"), any(), any()))
                    .thenReturn(List.of(row(KEY_ACTIVE_LEASE, "vi", "x", null)));

            service.resolveSnapshot(KEY_ACTIVE_LEASE, ContractLanguage.VI);

            ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
            verify(repo).findActiveAt(eq(KEY_ACTIVE_LEASE), eq("vi"), any(), cap.capture());
            assertThat(cap.getValue().getPageSize()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("listActive / getHistory")
    class ListAndHistory {

        @Test
        @DisplayName("listActive returns all active rows mapped to DTOs")
        void listActive() {
            when(repo.findAllActiveAt(any())).thenReturn(List.of(
                    row(KEY_LANDLORD_FAULT, "vi", "x", null),
                    row(KEY_LANDLORD_FAULT, "en", "y", null)));

            List<LegalTemplateDto> dtos = service.listActive();

            assertThat(dtos).hasSize(2);
            assertThat(dtos).extracting(LegalTemplateDto::lang).containsExactly("vi", "en");
        }

        @Test
        @DisplayName("getHistory rejects unknown key")
        void historyUnknownKey() {
            assertThatThrownBy(() -> service.getHistory("NOPE"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("getHistory returns chronological versions")
        void history() {
            when(repo.findByTemplateKeyOrderByLangAscEffectiveAtDesc(KEY_LANDLORD_FAULT))
                    .thenReturn(List.of(
                            row(KEY_LANDLORD_FAULT, "vi", "v2", Instant.now()),
                            row(KEY_LANDLORD_FAULT, "vi", "v1", Instant.now().minus(30, ChronoUnit.DAYS))));

            List<LegalTemplateDto> dtos = service.getHistory(KEY_LANDLORD_FAULT);

            assertThat(dtos).hasSize(2);
            assertThat(dtos).extracting(LegalTemplateDto::text).containsExactly("v2", "v1");
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("rejects unknown template key")
        void createUnknownKey() {
            CreateLegalTemplateRequest req = new CreateLegalTemplateRequest(
                    "NOT_A_KEY", "vi", "x".repeat(60), null, null);
            assertThatThrownBy(() -> service.create(UUID.randomUUID(), req))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects effectiveAt in the past (more than 1s skew)")
        void rejectsBackdated() {
            CreateLegalTemplateRequest req = new CreateLegalTemplateRequest(
                    KEY_LANDLORD_FAULT, "vi", "x".repeat(60),
                    Instant.now().minus(5, ChronoUnit.MINUTES), null);
            assertThatThrownBy(() -> service.create(UUID.randomUUID(), req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("past");
        }

        @Test
        @DisplayName("inserts new version and expires previous active version atomically")
        void expiresPreviousVersion() {
            UUID actor = UUID.randomUUID();
            LegalTemplate prev = row(KEY_LANDLORD_FAULT, "vi", "old", Instant.now().minus(60, ChronoUnit.DAYS));
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("vi"), any(), any()))
                    .thenReturn(List.of(prev));
            when(repo.save(any(LegalTemplate.class))).thenAnswer(i -> i.getArgument(0));

            CreateLegalTemplateRequest req = new CreateLegalTemplateRequest(
                    KEY_LANDLORD_FAULT, "vi", "x".repeat(60), null, "law update 2026");

            LegalTemplateDto dto = service.create(actor, req);

            // Both saves: one to expire prev, one to insert fresh
            ArgumentCaptor<LegalTemplate> cap = ArgumentCaptor.forClass(LegalTemplate.class);
            verify(repo, times(2)).save(cap.capture());
            List<LegalTemplate> saved = cap.getAllValues();

            // First save: prev with expiredAt set
            assertThat(saved.get(0).getExpiredAt()).isNotNull();
            assertThat(saved.get(0).getExpiredBy()).isEqualTo(actor);
            // Second save: fresh row
            assertThat(saved.get(1).getText()).isEqualTo("x".repeat(60));
            assertThat(saved.get(1).getCreatedBy()).isEqualTo(actor);
            assertThat(saved.get(1).getNote()).isEqualTo("law update 2026");
            assertThat(dto.text()).isEqualTo("x".repeat(60));
        }

        @Test
        @DisplayName("when no previous active version exists, only one save happens")
        void firstVersion() {
            UUID actor = UUID.randomUUID();
            when(repo.findActiveAt(eq(KEY_LANDLORD_FAULT), eq("ja"), any(), any()))
                    .thenReturn(List.of());
            when(repo.save(any(LegalTemplate.class))).thenAnswer(i -> i.getArgument(0));

            CreateLegalTemplateRequest req = new CreateLegalTemplateRequest(
                    KEY_LANDLORD_FAULT, "ja", "y".repeat(60), null, null);

            service.create(actor, req);

            verify(repo, times(1)).save(any(LegalTemplate.class));
        }

        @Test
        @DisplayName("null effectiveAt defaults to now()")
        void nullEffectiveAt() {
            UUID actor = UUID.randomUUID();
            when(repo.findActiveAt(eq(KEY_ACTIVE_LEASE), eq("en"), any(), any()))
                    .thenReturn(List.of());
            when(repo.save(any(LegalTemplate.class))).thenAnswer(i -> i.getArgument(0));

            CreateLegalTemplateRequest req = new CreateLegalTemplateRequest(
                    KEY_ACTIVE_LEASE, "en", "z".repeat(60), null, null);
            Instant before = Instant.now().minus(1, ChronoUnit.SECONDS);

            service.create(actor, req);

            ArgumentCaptor<LegalTemplate> cap = ArgumentCaptor.forClass(LegalTemplate.class);
            verify(repo).save(cap.capture());
            assertThat(cap.getValue().getEffectiveAt())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(Instant.now().plus(1, ChronoUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("expire")
    class Expire {

        @Test
        @DisplayName("404 when id not found")
        void notFound() {
            UUID id = UUID.randomUUID();
            when(repo.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.expire(id, UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("rejects double-expire (already-expired template)")
        void alreadyExpired() {
            UUID id = UUID.randomUUID();
            LegalTemplate t = row(KEY_LANDLORD_FAULT, "vi", "x", null);
            t.setExpiredAt(Instant.now().minus(1, ChronoUnit.DAYS));
            when(repo.findById(id)).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.expire(id, UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already expired");
        }

        @Test
        @DisplayName("happy: sets expiredAt and expiredBy")
        void happy() {
            UUID id = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            LegalTemplate t = row(KEY_LANDLORD_FAULT, "vi", "x", null);
            when(repo.findById(id)).thenReturn(Optional.of(t));
            when(repo.save(any(LegalTemplate.class))).thenAnswer(i -> i.getArgument(0));

            service.expire(id, actor);

            assertThat(t.getExpiredAt()).isNotNull();
            assertThat(t.getExpiredBy()).isEqualTo(actor);
        }
    }
}
