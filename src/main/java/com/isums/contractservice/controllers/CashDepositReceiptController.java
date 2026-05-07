package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.ApiResponses;
import com.isums.contractservice.domains.dtos.CashDepositReceiptRequest;
import com.isums.contractservice.domains.dtos.CashDepositReceiptResponse;
import com.isums.contractservice.infrastructures.abstracts.CashDepositReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts")
@RequiredArgsConstructor
public class CashDepositReceiptController {

    private final CashDepositReceiptService service;

    @PostMapping("/{contractId}/deposit/cash-receipt")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<CashDepositReceiptResponse> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID contractId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody @Valid CashDepositReceiptRequest request) {
        CashDepositReceiptResponse dto = service.confirmCashDeposit(
                contractId, actorId(jwt), idempotencyKey, request);
        return ApiResponses.created(dto, "Cash deposit confirmed");
    }

    @GetMapping("/{contractId}/deposit/cash-receipt")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER','TENANT')")
    public ApiResponse<CashDepositReceiptResponse> getActive(@PathVariable UUID contractId) {
        CashDepositReceiptResponse dto = service.getActive(contractId);
        return ApiResponses.ok(dto, dto != null ? "Active receipt found" : "No active receipt");
    }

    @GetMapping("/{contractId}/deposit/cash-receipt/{receiptNumber}/pdf")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER','TENANT')")
    public ResponseEntity<ByteArrayResource> downloadPdf(
            @PathVariable UUID contractId,
            @PathVariable String receiptNumber) {
        byte[] pdf = service.renderReceiptPdf(contractId, receiptNumber);
        ByteArrayResource body = new ByteArrayResource(pdf);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + receiptNumber + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(body);
    }

    private UUID actorId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT subject");
        }
    }
}
