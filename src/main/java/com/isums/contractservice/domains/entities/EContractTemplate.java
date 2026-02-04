package com.isums.contractservice.domains.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "EContractTemplates")
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

    @Column(columnDefinition = "text")
    private String contentHtml;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
