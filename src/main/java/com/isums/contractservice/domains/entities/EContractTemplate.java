package com.isums.contractservice.domains.entities;

import com.isums.common.i18n.TranslationMap;
import com.isums.common.i18n.TranslationMapConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "econtract_templates", indexes = {
        @Index(name = "idx_template_code", columnList = "code", unique = true)
})
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class EContractTemplate {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_translations", columnDefinition = "text")
    @Convert(converter = TranslationMapConverter.class)
    private TranslationMap nameTranslations;

    @Column(name = "content_html", columnDefinition = "text")
    private String contentHtml;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;
}
