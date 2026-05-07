package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.CreateLegalTemplateRequest;
import com.isums.contractservice.domains.dtos.LegalTemplateDto;
import com.isums.contractservice.domains.entities.LegalTemplate;
import com.isums.contractservice.domains.enums.ContractLanguage;
import com.isums.contractservice.domains.enums.LegalTemplateKey;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.LegalTemplateService;
import com.isums.contractservice.infrastructures.repositories.LegalTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LegalTemplateServiceImpl implements LegalTemplateService {

    private static final String BILINGUAL_SEPARATOR = "\n\n— — —\n\n";

    private final LegalTemplateRepository repo;

    @Override
    @Transactional(readOnly = true)
    public String resolveSnapshot(String templateKey, ContractLanguage contractLang) {
        if (!LegalTemplateKey.isValid(templateKey)) {
            throw new IllegalArgumentException("Unknown legal template key: " + templateKey);
        }
        Instant now = Instant.now();
        String primary = fetchOne(templateKey, "vi", now).orElseThrow(() ->
                new IllegalStateException("Missing required VI legal template for key: " + templateKey
                        + ". Seed via Flyway migration before consumer service can submit."));

        if (contractLang == null || contractLang == ContractLanguage.VI) {
            return primary;
        }
        String secondaryLang = (contractLang == ContractLanguage.VI_JA) ? "ja" : "en";
        Optional<String> secondary = fetchOne(templateKey, secondaryLang, now);
        if (secondary.isEmpty()) {
            log.warn("[LegalTemplate] missing {} translation for key={}; snapshotting VI-only",
                    secondaryLang, templateKey);
            return primary;
        }
        return primary + BILINGUAL_SEPARATOR + secondary.get();
    }

    private Optional<String> fetchOne(String key, String lang, Instant now) {
        return repo.findActiveAt(key, lang, now, PageRequest.of(0, 1))
                .stream().findFirst()
                .map(LegalTemplate::getText);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LegalTemplateDto> listActive() {
        return repo.findAllActiveAt(Instant.now())
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LegalTemplateDto> getHistory(String templateKey) {
        if (!LegalTemplateKey.isValid(templateKey)) {
            throw new IllegalArgumentException("Unknown legal template key: " + templateKey);
        }
        return repo.findByTemplateKeyOrderByLangAscEffectiveAtDesc(templateKey)
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public LegalTemplateDto create(UUID actorId, CreateLegalTemplateRequest request) {
        if (!LegalTemplateKey.isValid(request.templateKey())) {
            throw new IllegalArgumentException("Unknown legal template key: " + request.templateKey());
        }
        Instant now = Instant.now();
        Instant effectiveAt = request.effectiveAt() != null ? request.effectiveAt() : now;
        // Allow ~1s clock skew but reject clearly-backdated effective times
        if (effectiveAt.isBefore(now.minusSeconds(1))) {
            throw new IllegalArgumentException(
                    "effectiveAt cannot be in the past (use a forward-dated value or omit to use now)");
        }

        // Auto-expire current active version (if any) at the new effective_at,
        // so lookups before that instant still return the old text and lookups
        // at/after return the new text. Both writes are in one transaction.
        repo.findActiveAt(request.templateKey(), request.lang(), effectiveAt, PageRequest.of(0, 1))
                .stream().findFirst().ifPresent(prev -> {
                    prev.setExpiredAt(effectiveAt);
                    prev.setExpiredBy(actorId);
                    repo.save(prev);
                    log.info("[LegalTemplate] expired previous version id={} key={} lang={} at={}",
                            prev.getId(), prev.getTemplateKey(), prev.getLang(), effectiveAt);
                });

        LegalTemplate fresh = repo.save(LegalTemplate.builder()
                .templateKey(request.templateKey())
                .lang(request.lang())
                .text(request.text())
                .effectiveAt(effectiveAt)
                .note(request.note())
                .createdBy(actorId)
                .build());
        log.info("[LegalTemplate] created id={} key={} lang={} effective={} by={}",
                fresh.getId(), fresh.getTemplateKey(), fresh.getLang(), fresh.getEffectiveAt(), actorId);
        return toDto(fresh);
    }

    @Override
    @Transactional
    public LegalTemplateDto expire(UUID id, UUID actorId) {
        LegalTemplate t = repo.findById(id).orElseThrow(() ->
                new NotFoundException("Legal template not found: " + id));
        if (t.getExpiredAt() != null) {
            throw new IllegalStateException("Legal template " + id + " is already expired");
        }
        Instant now = Instant.now();
        t.setExpiredAt(now);
        t.setExpiredBy(actorId);
        LegalTemplate saved = repo.save(t);
        log.info("[LegalTemplate] expired id={} key={} lang={} by={} at={}",
                saved.getId(), saved.getTemplateKey(), saved.getLang(), actorId, now);
        return toDto(saved);
    }

    private LegalTemplateDto toDto(LegalTemplate t) {
        return new LegalTemplateDto(
                t.getId(),
                t.getTemplateKey(),
                t.getLang(),
                t.getText(),
                t.getEffectiveAt(),
                t.getExpiredAt(),
                t.getNote(),
                t.getCreatedBy(),
                t.getExpiredBy(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
