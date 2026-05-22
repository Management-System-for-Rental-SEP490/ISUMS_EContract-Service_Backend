package com.isums.contractservice.domains.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String action;

    @Column(name = "enabled_before")
    private Boolean enabledBefore;

    @Column(name = "enabled_after")
    private Boolean enabledAfter;

    @Column(length = 32)
    private String scope;

    @Column(name = "title_vi", length = 200)
    private String titleVi;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
