package com.isums.contractservice.domains.entities;

import com.isums.contractservice.domains.enums.EContractStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "EContracts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EContract implements Serializable {

    @Id
    @GeneratedValue
    @UuidGenerator
    private String id;
    @Column(nullable = false)
    private String userId;
    private String name;
    @Column(nullable = false)
    private String snapshotKey;
    @Column(nullable = false)
    private EContractStatus status;
    private String CreatedBy;
    private Instant CreatedAt;
}
