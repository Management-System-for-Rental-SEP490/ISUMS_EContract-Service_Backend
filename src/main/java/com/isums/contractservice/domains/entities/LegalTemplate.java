package com.isums.contractservice.domains.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "legal_template", indexes = {
        @Index(name = "idx_legal_template_active",  columnList = "template_key,lang,effective_at"),
        @Index(name = "idx_legal_template_history", columnList = "template_key,lang,effective_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegalTemplate {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "template_key", nullable = false, length = 80)
    private String templateKey;

    @Column(nullable = false, length = 8)
    private String lang;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "expired_by")
    private UUID expiredBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
