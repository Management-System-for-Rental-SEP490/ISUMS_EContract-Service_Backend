package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.infrastructures.abstracts.EContractService;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts")
@RequiredArgsConstructor
public class EContractController {

    private final EContractService service;
    private final VnptEContractClient vnptClient;

    @PostMapping
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<EContractDto> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid CreateEContractRequest req) {
        return ApiResponses.created(
                service.createDraft(actorId(jwt), jwt.getTokenValue(), req),
                "Tạo hợp đồng thành công");
    }

    @GetMapping("/{id}")
    public ApiResponse<EContractDto> getById(@PathVariable UUID id) {
        return ApiResponses.ok(service.getById(id), "Success");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<List<EContractDto>> getAll() {
        return ApiResponses.ok(service.getAll(), "Success");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<EContractDto> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateEContractRequest req) {
        return ApiResponses.ok(service.updateContract(id, req), "Cập nhật thành công");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        service.deleteContract(id, actorId(jwt));
        return ApiResponses.ok(null, "Xóa hợp đồng thành công");
    }

    @PutMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<EContractDto> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return ApiResponses.ok(
                service.confirmByAdmin(id, actorId(jwt)),
                "Đã gửi hợp đồng cho tenant xem và xác nhận");
    }

    @PostMapping("/sign-admin")
    @PreAuthorize("hasRole('LANDLORD')")
    public ApiResponse<ProcessResponse> signAdmin(@RequestBody VnptProcessDto req) {
        return ApiResponses.ok(service.signByLandlord(req), "Landlord đã ký thành công");
    }

    /** Landlord huỷ hợp đồng (CORRECTING hoặc READY). */
    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasRole('LANDLORD')")
    public ApiResponse<Void> cancelByLandlord(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody @Valid TerminateContractRequest req) {
        service.cancelByLandlord(id, req.reason(), actorId(jwt));
        return ApiResponses.ok(null, "Đã huỷ hợp đồng");
    }

    @GetMapping("/{id}/pdf-url")
    public ApiResponse<String> getPdfUrl(@PathVariable UUID id, @RequestHeader("X-Contract-Token") String contractToken) {
        return ApiResponses.ok(service.getPdfPresignedUrl(id, contractToken),
                "Presigned URL hợp lệ trong 30 phút");
    }


    @PutMapping(value = "/{id}/cccd", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<VnptDocumentDto> tenantConfirmWithCccd(
            @PathVariable UUID id,
            @RequestParam("frontImage") MultipartFile frontImage,
            @RequestParam("backImage")  MultipartFile backImage,
            @RequestHeader("X-Contract-Token") String contractToken) {
        return ApiResponses.ok(
                service.tenantConfirmWithCccd(id, frontImage, backImage, contractToken),
                "Xác nhận thành công. Hợp đồng đã được gửi lên hệ thống ký điện tử.");
    }

    @GetMapping("/{id}/cccd-status")
    public ApiResponse<Boolean> cccdStatus(@PathVariable UUID id) {
        return ApiResponses.ok(service.hasCccd(id), "Success");
    }

    @PutMapping("/{id}/tenant-cancel")
    public ApiResponse<Void> cancelByTenant(
            @PathVariable UUID id,
            @RequestBody @Valid TerminateContractRequest req,
            @RequestHeader("X-Contract-Token") String contractToken) {
        service.cancelByTenant(id, req.reason(), null, contractToken);
        return ApiResponses.ok(null, "Đã từ chối hợp đồng");
    }

    @PostMapping("/processCode")
    public ApiResponse<ProcessLoginInfoDto> processCode(
            @RequestBody ProcessCodeLoginRequest req) {
        return ApiResponses.ok(
                service.getAccessInfoByProcessCode(req.processCode()),
                "Lấy thông tin ký thành công");
    }


    @PostMapping("/sign")
    public ApiResponse<ProcessResponse> sign(@RequestBody VnptProcessDto req) {
        return ApiResponses.ok(service.signByTenant(req), "Ký hợp đồng thành công");
    }


    @PostMapping("/outsystem")
    public ApiResponse<EContractDto> outSystem(@RequestBody ProcessCodeLoginRequest req) {
        return ApiResponses.ok(service.getOutSystem(req.processCode()), "Success");
    }

    @GetMapping("/vnpt-document/{documentId}")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<VnptDocumentDto> vnptDocument(@PathVariable String documentId) {
        return ApiResponses.ok(service.getVnptDocumentById(documentId), "Success");
    }

    @GetMapping("/test-token")
    @PreAuthorize("hasRole('LANDLORD')")
    public String testToken() {
        return vnptClient.getToken();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private UUID actorId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (Exception e) { throw new IllegalStateException("Invalid JWT subject"); }
    }
}