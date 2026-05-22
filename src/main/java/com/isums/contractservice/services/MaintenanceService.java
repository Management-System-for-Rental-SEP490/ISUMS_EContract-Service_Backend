package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.MaintenanceAuditDto;
import com.isums.contractservice.domains.dtos.MaintenanceStatusDto;
import com.isums.contractservice.domains.dtos.MaintenanceUpdateRequest;
import com.isums.contractservice.domains.entities.MaintenanceAuditLog;
import com.isums.contractservice.domains.entities.MaintenanceSettings;
import com.isums.contractservice.domains.enums.MaintenanceScope;
import com.isums.contractservice.domains.enums.MaintenanceSeverity;
import com.isums.contractservice.infrastructures.repositories.MaintenanceAuditLogRepository;
import com.isums.contractservice.infrastructures.repositories.MaintenanceSettingsRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceService {

    private static final Integer SINGLETON_ID = 1;
    private static final String CACHE = "maintenance-status";

    private final MaintenanceSettingsRepository settingsRepo;
    private final MaintenanceAuditLogRepository auditRepo;

    @Cacheable(value = CACHE, key = "'current'")
    @Transactional(readOnly = true)
    public MaintenanceStatusDto getStatus() {
        MaintenanceSettings s = settingsRepo.findById(SINGLETON_ID).orElse(null);
        if (s == null) {
            return new MaintenanceStatusDto(
                    false, MaintenanceScope.TENANT_PORTAL, MaintenanceSeverity.INFO,
                    "Hệ thống bảo trì", null, null,
                    "Hệ thống đang được bảo trì. Vui lòng quay lại sau.", null, null,
                    null, null, false, null, null, null,
                    1, Instant.EPOCH);
        }
        return toDto(s);
    }

    @Transactional
    public void ensureSingletonRow(UUID systemActor) {
        if (settingsRepo.findById(SINGLETON_ID).isPresent()) return;
        MaintenanceSettings s = MaintenanceSettings.builder()
                .id(SINGLETON_ID)
                .enabled(false)
                .scope(MaintenanceScope.TENANT_PORTAL)
                .severity(MaintenanceSeverity.INFO)
                .titleVi("Hệ thống bảo trì")
                .messageVi("Hệ thống đang được bảo trì. Vui lòng quay lại sau.")
                .allowReadOnly(false)
                .version(1)
                .updatedBy(systemActor)
                .updatedAt(Instant.now())
                .build();
        settingsRepo.save(s);
        log.info("[Maintenance] Seeded singleton row id={}", SINGLETON_ID);
    }

    @CacheEvict(value = CACHE, key = "'current'")
    @Transactional
    public MaintenanceStatusDto update(MaintenanceUpdateRequest req, UUID actorId, String actorEmail) {
        MaintenanceSettings s = settingsRepo.findById(SINGLETON_ID)
                .orElseThrow(() -> new EntityNotFoundException("Maintenance settings row missing"));

        Boolean enabledBefore = s.getEnabled();

        s.setEnabled(req.enabled());
        s.setScope(req.scope());
        s.setSeverity(req.severity() != null ? req.severity() : MaintenanceSeverity.INFO);
        s.setTitleVi(req.titleVi());
        s.setTitleEn(req.titleEn());
        s.setTitleJa(req.titleJa());
        s.setMessageVi(req.messageVi());
        s.setMessageEn(req.messageEn());
        s.setMessageJa(req.messageJa());
        s.setScheduledStart(req.scheduledStart());
        s.setScheduledEnd(req.scheduledEnd());
        s.setAllowReadOnly(req.allowReadOnly() != null && req.allowReadOnly());
        s.setContactEmail(req.contactEmail());
        s.setContactPhone(req.contactPhone());
        s.setStatusPageUrl(req.statusPageUrl());
        s.setUpdatedBy(actorId);
        s.setUpdatedByEmail(actorEmail);
        s.setUpdatedAt(Instant.now());

        MaintenanceSettings saved = settingsRepo.save(s);

        String action;
        if (!enabledBefore && req.enabled()) action = "ENABLED";
        else if (enabledBefore && !req.enabled()) action = "DISABLED";
        else action = "UPDATED";

        auditRepo.save(MaintenanceAuditLog.builder()
                .action(action)
                .enabledBefore(enabledBefore)
                .enabledAfter(req.enabled())
                .scope(req.scope().name())
                .titleVi(req.titleVi())
                .actorId(actorId)
                .actorEmail(actorEmail)
                .createdAt(Instant.now())
                .build());

        log.info("[Maintenance] {} by actorId={} actorEmail={} scope={} title=\"{}\"",
                action, actorId, actorEmail, req.scope(), req.titleVi());

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceAuditDto> audit(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return auditRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, safeLimit)).stream()
                .map(a -> new MaintenanceAuditDto(
                        a.getId(), a.getAction(),
                        a.getEnabledBefore(), a.getEnabledAfter(),
                        a.getScope(), a.getTitleVi(),
                        a.getActorId(), a.getActorEmail(), a.getCreatedAt()))
                .toList();
    }

    private MaintenanceStatusDto toDto(MaintenanceSettings s) {
        return new MaintenanceStatusDto(
                s.getEnabled(),
                s.getScope() != null ? s.getScope() : MaintenanceScope.TENANT_PORTAL,
                s.getSeverity() != null ? s.getSeverity() : MaintenanceSeverity.INFO,
                s.getTitleVi(), s.getTitleEn(), s.getTitleJa(),
                s.getMessageVi(), s.getMessageEn(), s.getMessageJa(),
                s.getScheduledStart(), s.getScheduledEnd(),
                s.getAllowReadOnly(),
                s.getContactEmail(), s.getContactPhone(), s.getStatusPageUrl(),
                s.getVersion(), s.getUpdatedAt());
    }
}
