package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.ApiResponses;
import com.isums.contractservice.domains.dtos.CompleteInspectionRequest;
import com.isums.contractservice.domains.dtos.ContractInspectionDto;
import com.isums.contractservice.infrastructures.abstracts.ContractTerminationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final ContractTerminationService contractTerminationService;

    @PostMapping("/{inspectionId}/complete")
    public ApiResponse<Void> completeInspection(
            @PathVariable UUID inspectionId,
            @RequestBody @Valid CompleteInspectionRequest req) {

        contractTerminationService.completeInspection(inspectionId, req);
        return ApiResponses.ok(null, "Inspection completed successfully");
    }

    @GetMapping("/inspections/{inspectionId}")
//    @PreAuthorize("hasAnyRole('LANDLORD', 'MANAGER', 'TECHNICAL_STAFF')")
    public ApiResponse<ContractInspectionDto> getInspection(
            @PathVariable UUID inspectionId) {
        return ApiResponses.ok(contractTerminationService.getInspectionById(inspectionId), "Success to get inspection");
    }

    @GetMapping("/{contractId}/inspection")
//    @PreAuthorize("hasAnyRole('LANDLORD', 'MANAGER')")
    public ApiResponse<ContractInspectionDto> getInspectionByContract(
            @PathVariable UUID contractId) {
        return ApiResponses.ok(contractTerminationService.getInspectionByContractId(contractId), "Success to get inspection");
    }
}
