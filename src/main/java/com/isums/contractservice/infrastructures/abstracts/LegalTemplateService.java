package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.CreateLegalTemplateRequest;
import com.isums.contractservice.domains.dtos.LegalTemplateDto;
import com.isums.contractservice.domains.enums.ContractLanguage;

import java.util.List;
import java.util.UUID;

public interface LegalTemplateService {

    /**
     * Resolves the active legal text for {@code templateKey}, formatted to
     * match the contract language. The returned String is what callers should
     * snapshot into their domain record (e.g.
     * {@code contract_relocation_requests.legal_basis}).
     *
     * <ul>
     *   <li>{@code VI} or {@code null} → returns the active VI text.</li>
     *   <li>{@code VI_EN} → returns VI + separator + EN.</li>
     *   <li>{@code VI_JA} → returns VI + separator + JA.</li>
     * </ul>
     *
     * Falls back to VI-only with a WARN log if the secondary language has not
     * been seeded — never produces an empty result. Throws if the required VI
     * version is missing (mis-seeded environment).
     */
    String resolveSnapshot(String templateKey, ContractLanguage contractLang);

    List<LegalTemplateDto> listActive();

    List<LegalTemplateDto> getHistory(String templateKey);

    LegalTemplateDto create(UUID actorId, CreateLegalTemplateRequest request);

    LegalTemplateDto expire(UUID id, UUID actorId);
}
