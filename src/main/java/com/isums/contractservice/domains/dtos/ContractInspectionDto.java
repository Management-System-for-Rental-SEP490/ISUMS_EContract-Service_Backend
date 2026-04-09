package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.entities.ContractInspection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractInspectionDto {
    private UUID id;
    private UUID contractId;
    private UUID houseId;
    private String status;
    private UUID inspectorStaffId;
    private String notes;
    private Long deductionAmount;
    private List<String> photoUrls;
    private Instant createdAt;
    private Instant completedAt;

    public static ContractInspectionDto from(ContractInspection i) {
        return ContractInspectionDto.builder()
                .id(i.getId())
                .contractId(i.getContractId())
                .houseId(i.getHouseId())
                .status(i.getStatus().name())
                .inspectorStaffId(i.getInspectorStaffId())
                .notes(i.getNotes())
                .deductionAmount(i.getDeductionAmount())
                .photoUrls(i.getPhotoUrls())
                .createdAt(i.getCreatedAt())
                .completedAt(i.getCompletedAt())
                .build();
    }
}