package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.services.MaintenanceService;
import com.isums.contractservice.services.MaintenanceTranslateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/system/maintenance")
@RequiredArgsConstructor
@Tag(name = "System Maintenance")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;
    private final MaintenanceTranslateService translateService;

    @Operation(summary = "[PUBLIC] Get current maintenance status (no auth, cacheable 5s)")
    @GetMapping("/status")
    public ApiResponse<MaintenanceStatusDto> status() {
        return ApiResponses.ok(maintenanceService.getStatus(), "ok");
    }

    @Operation(
            summary = "[ADMIN] Update maintenance settings (toggle on/off, edit message, schedule)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @PutMapping
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<MaintenanceStatusDto> update(
            @Valid @RequestBody MaintenanceUpdateRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        String actorEmail = jwt.getClaimAsString("email");
        MaintenanceStatusDto saved = maintenanceService.update(req, actorId, actorEmail);
        return ApiResponses.ok(saved, "Maintenance settings updated");
    }

    @Operation(
            summary = "[ADMIN] Audit log of maintenance toggles",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<List<MaintenanceAuditDto>> audit(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponses.ok(maintenanceService.audit(limit), "ok");
    }

    @Operation(
            summary = "[ADMIN] Translate maintenance text via AWS Translate (proxy)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/translate")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<TranslateResponse> translate(@Valid @RequestBody TranslateRequest req) {
        String translated = translateService.translate(req.sourceText(), req.targetLang());
        return ApiResponses.ok(new TranslateResponse(translated), "ok");
    }
}
