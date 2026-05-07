package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.LegalTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LegalTemplateRepository extends JpaRepository<LegalTemplate, UUID> {

    /**
     * Most-recent active version for (key, lang) at the given instant.
     * Caller passes {@code Pageable.ofSize(1)} to enforce LIMIT 1.
     */
    @Query("""
        SELECT t FROM LegalTemplate t
        WHERE t.templateKey = :key
          AND t.lang = :lang
          AND t.effectiveAt <= :asOf
          AND (t.expiredAt IS NULL OR t.expiredAt > :asOf)
        ORDER BY t.effectiveAt DESC
        """)
    List<LegalTemplate> findActiveAt(
            @Param("key") String templateKey,
            @Param("lang") String lang,
            @Param("asOf") Instant asOf,
            Pageable limit);

    List<LegalTemplate> findByTemplateKeyOrderByLangAscEffectiveAtDesc(String templateKey);

    /** All currently-active rows across keys/langs — for admin overview screen. */
    @Query("""
        SELECT t FROM LegalTemplate t
        WHERE t.expiredAt IS NULL
          AND t.effectiveAt <= :asOf
        ORDER BY t.templateKey, t.lang, t.effectiveAt DESC
        """)
    List<LegalTemplate> findAllActiveAt(@Param("asOf") Instant asOf);
}
