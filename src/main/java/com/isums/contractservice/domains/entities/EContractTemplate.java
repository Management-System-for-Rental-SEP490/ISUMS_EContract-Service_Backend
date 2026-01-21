package com.isums.contractservice.domains.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

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
    private String id;
    @Column(nullable = false)
    private String code;
    private String name;
    private String contentHtml;
    private Instant createdAt;
}
