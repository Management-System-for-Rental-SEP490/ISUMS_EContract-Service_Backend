package com.isums.contractservice.domains.entities;

import com.isums.contractservice.domains.enums.MaintenanceScope;
import com.isums.contractservice.domains.enums.MaintenanceSeverity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "maintenance_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private Boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MaintenanceScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MaintenanceSeverity severity;

    @Column(name = "title_vi", nullable = false, length = 200)
    private String titleVi;

    @Column(name = "title_en", length = 200)
    private String titleEn;

    @Column(name = "title_ja", length = 200)
    private String titleJa;

    @Column(name = "message_vi", nullable = false, columnDefinition = "TEXT")
    private String messageVi;

    @Column(name = "message_en", columnDefinition = "TEXT")
    private String messageEn;

    @Column(name = "message_ja", columnDefinition = "TEXT")
    private String messageJa;

    @Column(name = "scheduled_start")
    private Instant scheduledStart;

    @Column(name = "scheduled_end")
    private Instant scheduledEnd;

    @Column(name = "allow_read_only", nullable = false)
    private Boolean allowReadOnly;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "status_page_url", length = 255)
    private String statusPageUrl;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "updated_by_email", length = 255)
    private String updatedByEmail;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
