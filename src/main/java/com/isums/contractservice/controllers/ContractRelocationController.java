package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.ApiResponses;
import com.isums.contractservice.domains.dtos.ContractRelocationRequestDto;
import com.isums.contractservice.domains.dtos.DepositBookableHouseDto;
import com.isums.contractservice.domains.dtos.CreateLandlordFaultRelocationRequest;
import com.isums.contractservice.domains.dtos.CreateRelocationRequest;
import com.isums.contractservice.domains.dtos.EContractDto;
import com.isums.contractservice.domains.dtos.ReviewRelocationRequest;
import com.isums.contractservice.infrastructures.abstracts.ContractRelocationService;
import common.statics.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts")
@RequiredArgsConstructor
public class ContractRelocationController {

    private final ContractRelocationService relocationService;

    @PostMapping("/{contractId}/relocation-requests")
    @PreAuthorize("hasRole('TENANT')")
    public ApiResponse<ContractRelocationRequestDto> submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID contractId,
            @RequestBody @Valid CreateRelocationRequest request) {
        return ApiResponses.created(
                relocationService.submit(contractId, actorId(jwt), request),
                "Relocation request submitted");
    }

    @PostMapping(
            value = "/relocation-requests/staff-report",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('TECHNICAL_STAFF','MANAGER','LANDLORD')")
    public ApiResponse<ContractRelocationRequestDto> reportLandlordFaultByContractNumber(
            @AuthenticationPrincipal Jwt jwt,
            Authentication auth,
            @RequestParam String contractNumber,
            @RequestParam(required = false) UUID recommendedHouseId,
            @RequestParam String reportReason,
            @RequestPart(value = "evidenceFiles", required = false) List<MultipartFile> evidenceFiles) {
        CreateLandlordFaultRelocationRequest request =
                new CreateLandlordFaultRelocationRequest(recommendedHouseId, reportReason, null);
        return ApiResponses.created(
                relocationService.reportLandlordFaultByContractNumber(
                        contractNumber, actorId(jwt), isLandlord(auth), request, evidenceFiles),
                "Landlord-fault relocation report submitted");
    }

    @GetMapping("/{contractId}/relocation-requests/active")
    @PreAuthorize("hasAnyRole('TECHNICAL_STAFF','MANAGER','LANDLORD','TENANT')")
    public ApiResponse<ContractRelocationRequestDto> activeByContract(@PathVariable UUID contractId) {
        ContractRelocationRequestDto dto = relocationService.getActiveByContractId(contractId).orElse(null);
        return ApiResponses.ok(dto, dto != null ? "Active relocation found" : "No active relocation");
    }

    @GetMapping("/{contractId}/relocation-link")
    @PreAuthorize("hasAnyRole('TECHNICAL_STAFF','MANAGER','LANDLORD','TENANT')")
    public ApiResponse<ContractRelocationRequestDto> relocationLink(@PathVariable UUID contractId) {
        ContractRelocationRequestDto dto = relocationService.getLinkByContractId(contractId).orElse(null);
        return ApiResponses.ok(dto, dto != null ? "Relocation link found" : "No relocation link");
    }

    @PostMapping(
            value = "/{contractId}/relocation-requests/staff-report",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_TECHNICAL_STAFF','ROLE_MANAGER','ROLE_LANDLORD')")
    public ApiResponse<ContractRelocationRequestDto> reportLandlordFaultByContractId(
            @AuthenticationPrincipal Jwt jwt,
            Authentication auth,
            @PathVariable UUID contractId,
            @RequestParam(required = false) UUID recommendedHouseId,
            @RequestParam String reportReason,
            @RequestPart(value = "evidenceFiles", required = false) List<MultipartFile> evidenceFiles) {
        CreateLandlordFaultRelocationRequest request =
                new CreateLandlordFaultRelocationRequest(recommendedHouseId, reportReason, null);
        return ApiResponses.created(
                relocationService.reportLandlordFaultByContractId(
                        contractId, actorId(jwt), isLandlord(auth), request, evidenceFiles),
                "Landlord-fault relocation report submitted");
    }

    @GetMapping("/relocation-requests/my")
    @PreAuthorize("hasRole('TENANT')")
    public ApiResponse<List<ContractRelocationRequestDto>> myRequests(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.ok(relocationService.getMine(actorId(jwt)), "Success");
    }

    @GetMapping("/relocation-requests")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER','TECHNICAL_STAFF')")
    public ApiResponse<List<ContractRelocationRequestDto>> allRequests(
            @AuthenticationPrincipal Jwt jwt,
            Authentication auth) {
        return ApiResponses.ok(relocationService.getAll(actorId(jwt), isLandlord(auth)), "Success");
    }

    @PatchMapping("/relocation-requests/{requestId}/review")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<ContractRelocationRequestDto> review(
            @AuthenticationPrincipal Jwt jwt,
            Authentication auth,
            @PathVariable UUID requestId,
            @RequestBody @Valid ReviewRelocationRequest request) {
        return ApiResponses.ok(
                relocationService.review(requestId, actorId(jwt), isLandlord(auth), request),
                "Relocation reviewed");
    }

    @PostMapping("/relocation-requests/{requestId}/accept-quote")
    @PreAuthorize("hasRole('TENANT')")
    public ApiResponse<ContractRelocationRequestDto> acceptQuote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId) {
        return ApiResponses.ok(
                relocationService.acceptQuote(requestId, actorId(jwt)),
                "Relocation quote accepted");
    }

    @PostMapping("/relocation-requests/{requestId}/replacement-contract")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<EContractDto> createReplacement(
            @AuthenticationPrincipal Jwt jwt,
            Authentication auth,
            @PathVariable UUID requestId) {
        return ApiResponses.created(
                relocationService.createReplacementContract(
                        requestId, actorId(jwt), isLandlord(auth), jwt.getTokenValue()),
                "Replacement contract created");
    }

    /** Tenant cancels their own request (only REQUESTED or QUOTED). */
    @PostMapping("/relocation-requests/{requestId}/cancel")
    @PreAuthorize("hasRole('TENANT')")
    public ApiResponse<ContractRelocationRequestDto> cancelByTenant(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId) {
        return ApiResponses.ok(
                relocationService.cancelByTenant(requestId, actorId(jwt)),
                "Relocation request cancelled");
    }

    /** Manager/landlord cancels (only before contract is created). */
    @PostMapping("/relocation-requests/{requestId}/cancel-by-manager")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<ContractRelocationRequestDto> cancelByManager(
            @AuthenticationPrincipal Jwt jwt,
            Authentication auth,
            @PathVariable UUID requestId) {
        return ApiResponses.ok(
                relocationService.cancelByManager(requestId, actorId(jwt), isLandlord(auth)),
                "Relocation request cancelled by manager");
    }

    /** Manager confirms physical handover of old premises after replacement is signed. */
    @PostMapping("/relocation-requests/{requestId}/confirm-handover")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<ContractRelocationRequestDto> confirmHandover(
            @AuthenticationPrincipal Jwt jwt,
            Authentication auth,
            @PathVariable UUID requestId) {
        return ApiResponses.ok(
                relocationService.confirmHandover(requestId, actorId(jwt), isLandlord(auth)),
                "Handover confirmed; relocation completed");
    }

    /**
     * Marketplace: list houses currently rented but eligible for early
     * deposit-booking (active contract ending within 30 days, no renewal,
     * no relocation in flight). FE merges with {@code GET /api/houses?status=AVAILABLE}
     * to display the full marketplace.
     */
    @GetMapping("/marketplace/deposit-bookable-houses")
    @PreAuthorize("hasAnyRole('TENANT','MANAGER','LANDLORD')")
    public ApiResponse<List<DepositBookableHouseDto>> depositBookableHouses(
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.ok(
                relocationService.findDepositBookableHouses(actorId(jwt)),
                "Success");
    }

    @GetMapping("/marketplace/locked-house-ids")
    @PreAuthorize("hasAnyRole('TENANT','MANAGER','LANDLORD')")
    public ApiResponse<java.util.Set<UUID>> lockedHouseIds() {
        return ApiResponses.ok(
                relocationService.findLockedHouseIdsForCreate(),
                "Success");
    }

    private UUID actorId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT subject");
        }
    }

    private boolean isLandlord(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_LANDLORD".equals(a.getAuthority()));
    }
}
