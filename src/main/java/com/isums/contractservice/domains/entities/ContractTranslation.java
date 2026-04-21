package com.isums.contractservice.domains.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Cache of AWS Translate results keyed by SHA-256(sourceText)+sourceLang+targetLang.
 * Avoids re-billing when boilerplate clauses repeat across contracts.
 */
@Entity
@Table(name = "contract_translations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_contract_translations",
                columnNames = {"source_hash", "source_language", "target_language"}),
        indexes = @Index(
                name = "idx_contract_translations_lookup",
                columnList = "source_hash,source_language,target_language"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractTranslation {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Column(name = "source_language", nullable = false, length = 8)
    private String sourceLanguage;

    @Column(name = "target_language", nullable = false, length = 8)
    private String targetLanguage;

    @Column(name = "source_text", nullable = false, columnDefinition = "text")
    private String sourceText;

    @Column(name = "translated_text", nullable = false, columnDefinition = "text")
    private String translatedText;

    @Column(name = "hit_count", nullable = false)
    @Builder.Default
    private Long hitCount = 1L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
