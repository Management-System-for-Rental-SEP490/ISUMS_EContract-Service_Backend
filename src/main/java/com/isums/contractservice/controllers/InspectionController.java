package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.ApiResponses;
import com.isums.contractservice.domains.dtos.CompleteInspectionRequest;
import com.isums.contractservice.services.ContractTerminationServiceimpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final ContractTerminationServiceimpl contractTerminationServiceimpl;

    @PostMapping("/{inspectionId}/complete")
    public ApiResponse<Void> completeInspection(
            @PathVariable UUID inspectionId,
            @RequestBody @Valid CompleteInspectionRequest req) {

        contractTerminationServiceimpl.completeInspection(inspectionId, req);
        return ApiResponses.ok(null, "Inspection completed successfully");
    }
}
