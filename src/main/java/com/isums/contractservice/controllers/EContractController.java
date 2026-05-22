package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.infrastructures.abstracts.ContractTerminationService;
import com.isums.contractservice.infrastructures.abstracts.DashboardService;
import com.isums.contractservice.infrastructures.abstracts.EContractService;
import com.isums.contractservice.infrastructures.abstracts.PowerCutService;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import common.paginations.dtos.PageRequestParams;
import common.paginations.dtos.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(
        name = "EContract",
        description = """
                ISUMS電子契約管理API。

                **契約ライフサイクル:**
                ```
                DRAFT → [confirm] → PENDING_TENANT_REVIEW → [tenant upload CCCD] → READY
                      → [landlord署名] → IN_PROGRESS → [tenant署名] → COMPLETED
                ```

                **Admin/Managerフロー:**
                1. `POST /` — 契約を作成 (DRAFT)
                2. `PUT /{id}` — 内容を編集
                3. `PUT /{id}/confirm` — 確認し、PDFリンク付きメールをtenantへ送信
                4. `POST /sign-admin` — VNPT上でLandlordが署名（tenant確認後）

                **Tenantフロー（JWT不要、メール内の X-Contract-Token を使用）:**
                1. `GET /{id}/pdf-url` — PDF閲覧リンクを取得
                2. `PUT /{id}/cccd` — CCCDをアップロードして契約に同意
                3. `POST /processCode` — Landlord署名後に署名情報を取得
                4. `POST /sign` — VNPT上で電子契約に署名
                """
)
@RestController
@RequestMapping("/api/econtracts")
@RequiredArgsConstructor
public class EContractController {

    private final EContractService service;
    private final DashboardService dashboardService;
    private final VnptEContractClient vnptClient;
    private final PowerCutService powerCutService;
    private final ContractTerminationService terminationService;
    private final com.isums.contractservice.services.ContractSnapshotRefreshService snapshotRefreshService;

    @Operation(
            summary = "Contract & property dashboard statistics",
            description = """
                    Returns:
                    - **propertyStats**: total properties, rented, vacant, expiring soon (30 days)
                    - **contractTimeSeries**: contracts created per month (period=6M or 1Y)
                    - **contractStatusBreakdown**: contract counts by status
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<DashboardResponse> dashboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "6M") String period,
            org.springframework.security.core.Authentication auth) {
        boolean isLandlord = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_LANDLORD"));
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                dashboardService.getDashboard(actorId(jwt), period, isLandlord), "Success");
    }

    @Operation(
            summary = "新しい契約を作成",
            description = """
                    **DRAFT** 状態の電子契約を作成します。

                    - 入力情報に基づいてテンプレートから契約HTMLを生成します。
                    - `isNewAccount = true` の場合: tenant用のVNPTアカウントとKeycloakアカウントを自動作成します。
                    - `isNewAccount = false` の場合: メールアドレスで既存tenantを検索します。

                    > **注意:** 契約作成前に、Landlordは `PUT /landlord-profiles/me` にて法的情報を更新しておく必要があります。
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "契約の作成に成功しました"),
            @ApiResponse(responseCode = "400", description = "無効なデータ、またはLandlord情報が未更新です"),
            @ApiResponse(responseCode = "403", description = "LANDLORD または MANAGER 権限がありません"),
            @ApiResponse(responseCode = "404", description = "houseId または tenant(email) が見つかりません")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<EContractDto> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid CreateEContractRequest req) {
        return com.isums.contractservice.domains.dtos.ApiResponses.created(
                service.createDraft(actorId(jwt), jwt.getTokenValue(), req),
                "Contract created successfully");
    }

    @Operation(
            summary = "契約詳細を取得",
            description = "現在の状態と契約内容HTMLを含む契約情報を返します。このエンドポイントは公開されており、tenant側FEで内容を読み込むために使用されます。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません")
    })
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public com.isums.contractservice.domains.dtos.ApiResponse<EContractDto> getById(
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id,
            org.springframework.security.core.Authentication auth) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.getById(id, auth), "Success");
    }

    @Operation(
            summary = "すべての契約一覧を取得",
            description = "作成日の昇順でソートされた全契約を返します。",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "403", description = "権限がありません")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER','TENANT')")
    public com.isums.contractservice.domains.dtos.ApiResponse<PageResponse<EContractDto>> getAll(
            @ParameterObject @Valid @ModelAttribute PageRequestParams params,
            org.springframework.security.core.Authentication auth) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.getAll(params.toPageRequest(), auth), "Success");
    }

    @Operation(
            summary = "契約を編集",
            description = """
                    契約内容を更新します。動作は現在の状態によって異なります。

                    | 現在の状態 | 結果 |
                    |---|---|
                    | `DRAFT` | 通常更新 |
                    | `PENDING_TENANT_REVIEW` | `CORRECTING` に変更し、tenant閲覧中を **WebSocket** で通知 |
                    | `CORRECTING` | 引き続き編集可能 |
                    | その他 | 400エラー |

                    **WebSocket通知** (tenant は `/topic/contract/{id}/status` を購読):
                    ```json
                    {
                      "contractId": "...",
                      "status": "CORRECTING",
                      "message": "契約は家主によって修正中です。新しいバージョンをお待ちください。"
                    }
                    ```
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新に成功しました"),
            @ApiResponse(responseCode = "400", description = "現在の状態では編集できません"),
            @ApiResponse(responseCode = "403", description = "権限がありません"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<EContractDto> update(
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id,
            @RequestBody @Valid UpdateEContractRequest req) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.updateContract(id, req), "Updated successfully");
    }

    @Operation(
            summary = "契約を削除",
            description = "契約が **DRAFT** 状態（tenantへ未送信）の場合のみ削除できます。DBおよびS3から完全に削除されます。",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "削除に成功しました"),
            @ApiResponse(responseCode = "400", description = "DRAFT状態以外の契約は削除できません"),
            @ApiResponse(responseCode = "403", description = "権限がありません"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id) {
        service.deleteContract(id, actorId(jwt));
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(null, "Contract deleted successfully");
    }

    @Operation(
            summary = "契約を確認し tenant にメール送信",
            description = """
                    契約を確認し、tenantに通知メールを送信します。

                    **実行ステップ:**
                    1. HTMLをPDFへ変換（スナップショットとしてS3に保存）
                    2. Magic token（TTL 24時間）を生成してRedisに保存
                    3. Kafka event を送信 → notification-service → tenantへメール送信:
                       - PDF閲覧リンク（presigned S3 URL、24時間有効）
                       - magic token付き確認リンク

                    **再送信可能**: `PENDING_TENANT_REVIEW` 状態でも再度呼び出せます（tenantがメールを受信していない場合）。

                    | 許可される状態 | 結果 |
                    |---|---|
                    | `DRAFT` | 新しいPDF + 初回メール送信 |
                    | `CORRECTING` | 新しいPDF + 修正後メール送信 |
                    | `PENDING_TENANT_REVIEW` | 新しいPDF + メール再送信 |
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "tenant へメールを送信しました"),
            @ApiResponse(responseCode = "400", description = "契約が確認可能な状態ではありません"),
            @ApiResponse(responseCode = "403", description = "権限がありません"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません")
    })
    @PutMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<EContractDto> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                service.confirmByAdmin(id, actorId(jwt)),
                "Contract sent to tenant for review and confirmation");
    }

    @Operation(
            summary = "Landlord が VNPT 上で契約に署名",
            description = """
                    Landlord がVNPTシステム上で電子署名を行います。

                    **要件:** 契約は `READY` 状態（tenantがCCCDをアップロードして確認済み）である必要があります。

                    **署名成功後:** 契約は `IN_PROGRESS` に遷移し、VNPTがtenantへ署名リンク付きメールを送信します。

                    **`processCode` と `token` の取得方法:** `GET /vnpt-document/{documentId}` を呼び出してprocess情報を取得します。
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Landlord の署名に成功しました"),
            @ApiResponse(responseCode = "400", description = "契約がREADY状態ではない、またはVNPTエラーです"),
            @ApiResponse(responseCode = "403", description = "LANDLORD 権限がありません")
    })
    @PostMapping("/sign-admin")
    @PreAuthorize("hasRole('LANDLORD')")
    public com.isums.contractservice.domains.dtos.ApiResponse<ProcessResponse> signAdmin(
            @RequestBody VnptProcessDto req) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.signByLandlord(req), "Landlord signed successfully");
    }

    @Operation(
            summary = "Manually refresh the PDF snapshot from VNPT",
            description = """
                    Admin/LANDLORD recovery endpoint. Khi async snapshot refresh
                    (after each signing) all 5 retry attempts fail (VNPT is very slow, >47s),
                    snapshotKey on S3 becomes stale. This endpoint force-refreshes
                    immediately:

                    - Fetch the latest PDF from VNPT (`/api/documents/{documentId}`)
                    - Compare SHA-256 against the existing S3 snapshot
                    - If different → upload to S3 with a new key, update `snapshotKey`
                    - If identical → keep as is

                    **Not async** — blocks the HTTP response for up to ~47s (5 attempts
                    × exponential backoff). Call from the Admin UI on demand; do not use
                    for automated flows (use the async pipeline instead).
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Snapshot refreshed or confirmed unchanged"),
            @ApiResponse(responseCode = "404", description = "Contract not found"),
            @ApiResponse(responseCode = "403", description = "Permission denied")
    })
    @PostMapping("/{id}/snapshot/refresh")
    @PreAuthorize("hasAnyRole('LANDLORD','ADMIN','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<SnapshotRefreshResultDto> refreshSnapshot(
            @Parameter(description = "Contract ID", required = true) @PathVariable UUID id) {
        boolean updated = snapshotRefreshService.refreshSync(id, "admin manual refresh");
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                new SnapshotRefreshResultDto(id, updated),
                updated ? "Snapshot updated" : "Snapshot unchanged (VNPT has not finished rendering)");
    }

    public record SnapshotRefreshResultDto(UUID contractId, boolean updated) { }

    @Operation(
            summary = "Landlord が契約をキャンセル",
            description = """
                    Landlord が契約をキャンセルします。`CORRECTING` または `READY` 状態のみ許可されます。

                    契約は `CANCELLED_BY_LANDLORD` に遷移します。
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "キャンセルに成功しました"),
            @ApiResponse(responseCode = "400", description = "現在の状態ではキャンセルできません"),
            @ApiResponse(responseCode = "403", description = "LANDLORD 権限がありません"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません")
    })
    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasRole('LANDLORD')")
    public com.isums.contractservice.domains.dtos.ApiResponse<Void> cancelByLandlord(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id,
            @RequestBody @Valid TerminateContractRequest req) {
        service.cancelByLandlord(id, req.reason(), actorId(jwt));
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(null, "Contract cancelled");
    }

    @Operation(
            summary = "[TENANT] 契約PDF閲覧用の presigned URL を取得",
            description = """
                    ブラウザ上（iframe / PDF.js viewer）でPDFを表示するための presigned S3 URL を返します。

                    **必要なヘッダー:** `X-Contract-Token` — メールで受け取った magic token（TTL 24時間）。

                    **FEでの用途:**
                    - tenant が契約内容PDFを閲覧する
                    - Presigned URL の有効期限は **30分** のため、期限切れの場合は再度呼び出してください

                    > このエンドポイントを呼び出しても、Token は **invalidateされません**。tenant は複数回閲覧できます。
                    """,
            parameters = @Parameter(
                    name = "X-Contract-Token",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "契約確認メールで受け取った magic token（TTL 24時間）"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "30分有効な presigned URL を返します"),
            @ApiResponse(responseCode = "400", description = "Token が無効、期限切れ、または契約と一致しません"),
            @ApiResponse(responseCode = "404", description = "契約、またはPDFスナップショットが見つかりません")
    })
    @GetMapping("/{id}/pdf-url")
    public com.isums.contractservice.domains.dtos.ApiResponse<String> getPdfUrl(
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id,
            @RequestHeader("X-Contract-Token") String contractToken) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                service.getPdfPresignedUrl(id, contractToken),
                "Presigned URL valid for 30 minutes");
    }

    @Operation(
            summary = "[TENANT] 契約のテナント種別と言語を取得",
            description = """
                    Outsystem の確認ページが、どの本人確認モーダル（CccdModal か
                    PassportModal か）を表示するかを決めるために呼び出します。

                    **必要なヘッダー:** `X-Contract-Token` — メールで受け取った magic token。
                    """,
            parameters = @Parameter(
                    name = "X-Contract-Token",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "契約確認メールで受け取った magic token（TTL 24時間）"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "tenantType + contractLanguage を返します"),
            @ApiResponse(responseCode = "400", description = "Token が無効、期限切れ、または契約と一致しません"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません")
    })
    @GetMapping("/{id}/tenant-meta")
    public com.isums.contractservice.domains.dtos.ApiResponse<com.isums.contractservice.domains.dtos.TenantMetaDto> getTenantMeta(
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id,
            @RequestHeader("X-Contract-Token") String contractToken) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                service.getTenantMeta(id, contractToken),
                "Tenant meta");
    }

    @Operation(
            summary = "[TENANT] 契約確認 + CCCDアップロード",
            description = """
                    Tenant は CCCD（市民身分証）をアップロードすることで契約への同意を確認します。

                    **必要なヘッダー:** `X-Contract-Token` — メールで受け取った magic token。

                    **実行ステップ:**
                    1. magic token を検証
                    2. OCR でCCCD画像から情報を読み取り
                    3. CCCD番号と氏名が契約情報と一致するか確認
                    4. CCCD画像をS3へアップロード
                    5. 契約PDFへCCCDページを追加
                    6. PDFをVNPTへアップロード + updateProcess（署名位置を SIGN_A/B アンカーから自動設定）
                    7. sendProcess — VNPT が関係者へメール送信
                    8. 契約は `READY` に遷移
                    9. Magic token は **invalidate** されます（1回限り）

                    **OCRでよくあるエラー:**
                    - 画像が暗い / ぼやけている → より鮮明に撮影してください
                    - CCCD番号が一致しない → CCCDが違う、または契約情報の入力誤り
                    - 氏名が一致しない → OCRは正規化後（アクセント除去・小文字化）に比較します

                    **Request:** `multipart/form-data`
                    - `frontImage`: CCCD表面画像 (jpg/png, 50KB–10MB)
                    - `backImage`: CCCD裏面画像 (jpg/png, 50KB–10MB)
                    """,
            parameters = @Parameter(
                    name = "X-Contract-Token",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "契約確認メールで受け取った magic token（1回限り使用可能）"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "確認成功。契約はVNPTへ送信されました"),
            @ApiResponse(responseCode = "400", description = "Token無効 / OCR失敗 / CCCD不一致 / 画像形式不正"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません"),
            @ApiResponse(responseCode = "500", description = "VNPT または S3 エラー")
    })
    @PutMapping(value = "/{id}/cccd", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.isums.contractservice.domains.dtos.ApiResponse<VnptDocumentDto> tenantConfirmWithCccd(
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id,
            @Parameter(description = "CCCD表面画像 (jpg/png, 50KB–10MB)", required = true)
            @RequestParam("frontImage") MultipartFile frontImage,
            @Parameter(description = "CCCD裏面画像 (jpg/png, 50KB–10MB)", required = true)
            @RequestParam("backImage") MultipartFile backImage,
            @RequestHeader("X-Contract-Token") String contractToken) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                service.tenantConfirmWithCccd(id, frontImage, backImage, contractToken),
                "Confirmation succeeded. Contract submitted to the e-sign system.");
    }

    @PostMapping(value = "/{id}/cccd", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.isums.contractservice.domains.dtos.ApiResponse<VnptDocumentDto> tenantConfirmWithCccdPost(
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id,
            @Parameter(description = "CCCD表面画像 (jpg/png, 50KB–10MB)", required = true)
            @RequestParam("frontImage") MultipartFile frontImage,
            @Parameter(description = "CCCD裏面画像 (jpg/png, 50KB–10MB)", required = true)
            @RequestParam("backImage") MultipartFile backImage,
            @RequestHeader("X-Contract-Token") String contractToken) {
        return tenantConfirmWithCccd(id, frontImage, backImage, contractToken);
    }

    @Operation(
            summary = "tenant が CCCD をアップロード済みか確認",
            description = "tenant がCCCD（表面・裏面の両方）を正常にアップロード済みであれば `true` を返します。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "true = CCCDあり、false = 未アップロード"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません")
    })
    @GetMapping("/{id}/cccd-status")
    public com.isums.contractservice.domains.dtos.ApiResponse<Boolean> cccdStatus(
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.hasCccd(id), "Success");
    }

    @Operation(
            summary = "[FOREIGN TENANT] Confirm contract with passport",
            description = """
                    For foreign tenants (`tenantType = FOREIGNER`).

                    Flow:
                    1. Magic-token authentication (1-time use)
                    2. OCR the passport info page — parse MRZ TD3 per ICAO 9303 (passport number, full name, nationality, DOB, gender, expiry)
                    3. Cross-check passport number + full name against the contract
                    4. Verify the passport has not expired
                    5. Upload the image to S3
                    6. Append a passport appendix page to the PDF
                    7. Create document + updateProcess + sendProcess to VNPT
                    8. Contract → READY, token invalidate

                    **Common errors:**
                    - MRZ unreadable → capture the bottom two passport lines clearly
                    - Passport number mismatch → verify it was entered correctly at contract creation
                    - Passport already expired → renew before signing
                    """,
            parameters = @Parameter(
                    name = "X-Contract-Token",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "Magic token received via email (1-time use)"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Confirmation succeeded"),
            @ApiResponse(responseCode = "400", description = "Token invalid / OCR fail / passport mismatch / expired"),
            @ApiResponse(responseCode = "404", description = "Contract not found"),
            @ApiResponse(responseCode = "500", description = "VNPT or S3 error")
    })
    @PutMapping(value = "/{id}/passport", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.isums.contractservice.domains.dtos.ApiResponse<VnptDocumentDto> tenantConfirmWithPassport(
            @Parameter(description = "Contract ID", required = true) @PathVariable UUID id,
            @Parameter(description = "Passport info page image (jpg/png, 50KB–10MB)", required = true)
            @RequestParam("passportImage") MultipartFile passportImage,
            @RequestHeader("X-Contract-Token") String contractToken) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                service.tenantConfirmWithPassport(id, passportImage, contractToken),
                "Confirmation succeeded. Contract submitted to the e-sign system.");
    }

    @Operation(
            summary = "Has the tenant uploaded the passport?",
            description = "`true` if the tenant has uploaded the passport image."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "true = yes, false = not yet"),
            @ApiResponse(responseCode = "404", description = "Contract not found")
    })
    @GetMapping("/{id}/passport-status")
    public com.isums.contractservice.domains.dtos.ApiResponse<Boolean> passportStatus(
            @Parameter(description = "Contract ID", required = true) @PathVariable UUID id) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.hasPassport(id), "Success");
    }

    @Operation(
            summary = "[DEBUG] Replay notification once the tenant has confirmed Citizen ID",
            description = """
                    Re-emits the Kafka event `contract.ready-for-landlord-signature` for a contract in `READY` status.

                    Used to test the notification UI without re-running the full create-contract → tenant-upload-ID flow.
                    This endpoint does **not** change contract status; it only replays the notification event to the contract creator.
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notification event re-emitted"),
            @ApiResponse(responseCode = "403", description = "Only MANAGER may test this endpoint"),
            @ApiResponse(responseCode = "404", description = "Contract not found"),
            @ApiResponse(responseCode = "422", description = "Contract is not in READY or is missing the recipient")
    })
    @PostMapping("/{contractId}/notifications/ready-for-landlord-signature/test")
    @PreAuthorize("hasRole('MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<Void> testReadyForLandlordSignatureNotification(
            @Parameter(description = "Contract ID", required = true) @PathVariable UUID contractId) {
        service.triggerReadyForLandlordSignatureNotification(contractId);
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                null, "Replayed landlord-signing notification");
    }

    @Operation(summary = "Resend the signing email to the tenant", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant signing email resent"),
            @ApiResponse(responseCode = "403", description = "Only LANDLORD or MANAGER may resend"),
            @ApiResponse(responseCode = "404", description = "Contract not found"),
            @ApiResponse(responseCode = "422", description = "Contract is not IN_PROGRESS, missing documentId, or VNPT rejected the request")
    })
    @PostMapping("/{contractId}/notifications/resend-tenant-signature")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<Void> resendTenantSignatureNotification(
            @Parameter(description = "Contract ID", required = true) @PathVariable UUID contractId) {
        service.resendTenantSignatureNotification(contractId);
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                null, "Tenant signing email resent");
    }

    @Operation(
            summary = "[ADMIN] Re-emit ContractCompleted event after fixing tenant email",
            description = """
                    Recovery endpoint for contracts whose deposit-payment email failed because
                    `tenantEmail` was blank when the contract reached COMPLETED. Caller may pass
                    `tenantEmail` to overwrite the contract's email before re-emitting; if omitted,
                    the existing DB value (and user-service lookup) are used as-is.

                    Re-emits `contract-completed-topic` via the Outbox. Payment-service will dedupe
                    on the DEPOSIT periodKey, so the deposit invoice is not recreated — only the
                    payment-link email is re-sent to the tenant.
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "ContractCompleted event re-emitted"),
            @ApiResponse(responseCode = "403", description = "Only LANDLORD or MANAGER may resend"),
            @ApiResponse(responseCode = "404", description = "Contract not found"),
            @ApiResponse(responseCode = "422", description = "Contract is not COMPLETED")
    })
    @PostMapping("/{contractId}/admin/resend-completion")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<Void> resendContractCompleted(
            @Parameter(description = "Contract ID", required = true) @PathVariable UUID contractId,
            @RequestBody(required = false) ResendCompletionRequest body) {
        String overrideEmail = body == null ? null : body.tenantEmail();
        service.resendContractCompletedEvent(contractId, overrideEmail);
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                null, "ContractCompleted event re-emitted");
    }

    @Operation(
            summary = "[TENANT] 契約を拒否 / キャンセル",
            description = """
                    Tenant が契約を拒否します。

                    **必要なヘッダー:** `X-Contract-Token`。

                    **許可される状態:**
                    - `PENDING_TENANT_REVIEW` — CCCDアップロード前にtenantが拒否
                    - `IN_PROGRESS` — Landlord署名後にtenantがキャンセル

                    契約は `CANCELLED_BY_TENANT` に遷移します。Token は **invalidate** されます。
                    """,
            parameters = @Parameter(
                    name = "X-Contract-Token",
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "メールで受け取った magic token"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "キャンセルに成功しました"),
            @ApiResponse(responseCode = "400", description = "Tokenが無効、または現在の状態ではキャンセルできません"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません")
    })
    @PutMapping("/{id}/tenant-cancel")
    public com.isums.contractservice.domains.dtos.ApiResponse<Void> cancelByTenant(
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id,
            @RequestBody @Valid TerminateContractRequest req,
            @RequestHeader("X-Contract-Token") String contractToken) {
        service.cancelByTenant(id, req.reason(), null, contractToken);
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(null, "Contract rejected");
    }

    @Operation(
            summary = "[TENANT] VNPTから署名情報を取得",
            description = """
                    Tenant は VNPT から `processCode` を含むメールを受け取ります。このエンドポイントを呼び出して署名用tokenを取得します。

                    **JWT や X-Contract-Token は不要** — VNPTメールの processCode を使用します。

                    **Response に含まれる情報:**
                    - `token` — 署名用のVNPT JWT token
                    - `processId` — 署名プロセスID
                    - `position` — PDF上の署名位置
                    - `pageSign` — 署名が必要なページ
                    - `isOtp` — OTPが必要かどうか
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "情報取得に成功しました"),
            @ApiResponse(responseCode = "400", description = "processCode が無効、または期限切れです")
    })
    @PostMapping("/processCode")
    public com.isums.contractservice.domains.dtos.ApiResponse<ProcessLoginInfoDto> processCode(
            @RequestBody ProcessCodeLoginRequest req) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                service.getAccessInfoByProcessCode(req.processCode()),
                "Signing info fetched successfully");
    }

    @Operation(
            summary = "[TENANT] tenant が VNPT 上で契約に署名",
            description = """
                    Tenant が電子契約に署名します。`/processCode` から token を取得した後に呼び出します。

                    **署名成功後:**
                    - 契約は `COMPLETED` に遷移
                    - tenantアカウントが有効化されます（未アクティブの場合）
                    - tenant が家に紐付けされます
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "署名成功。契約は COMPLETED になりました"),
            @ApiResponse(responseCode = "400", description = "VNPTエラー、またはtokenが無効です")
    })
    @PostMapping("/sign")
    public com.isums.contractservice.domains.dtos.ApiResponse<ProcessResponse> sign(
            @RequestBody VnptProcessDto req) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.signByTenant(req), "Contract signed successfully");
    }

    @Operation(
            summary = "[TENANT] process code から契約情報を取得",
            description = "Tenant は VNPT outsystem のリンクからアクセスします。processCode に対応する契約情報を返します。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "404", description = "契約が見つかりません")
    })
    @PostMapping("/outsystem")
    public com.isums.contractservice.domains.dtos.ApiResponse<EContractDto> outSystem(
            @RequestBody ProcessCodeLoginRequest req) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.getOutSystem(req.processCode()), "Success");
    }

    @Operation(
            summary = "VNPT上の document 情報を取得",
            description = "documentId に基づいて、VNPTシステム上の document の状態および詳細情報を確認します。",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "404", description = "VNPT上に document が見つかりません"),
            @ApiResponse(responseCode = "403", description = "権限がありません")
    })
    @GetMapping("/vnpt-document/{documentId}")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<VnptDocumentDto> vnptDocument(
            @Parameter(description = "VNPTシステム上の Document ID", required = true)
            @PathVariable String documentId) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                service.getVnptDocumentById(documentId), "Success");
    }

    @Operation(
            summary = "[TENANT] My contracts list",
            description = """
                    Returns every contract where the tenant is the lessee (JWT required).
                    Response does not include `html` or `snapshotKey`.
                    `pdfUrl` is populated when the contract is in PENDING_TENANT_REVIEW, READY,
                    IN_PROGRESS, or COMPLETED.
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Not logged in")
    })
    @GetMapping("/my")
    @PreAuthorize("hasRole('TENANT')")
    public com.isums.contractservice.domains.dtos.ApiResponse<List<TenantEContractDto>> getMyContracts(
            @AuthenticationPrincipal Jwt jwt) {

        UUID keycloakId = UUID.fromString(jwt.getSubject());
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.getMyContracts(keycloakId), "Success");
    }

    @Operation(
            summary = "[TENANT] Get presigned PDF URL for the contract (JWT)",
            description = """
                    Returns an S3 presigned URL valid for **30 minutes** so mobile can render the PDF.
                    Tenant may only view their own contract — wrong userId returns 403.
                    Call this endpoint again when the URL expires.
                    """,
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned URL valid for 30 minutes"),
            @ApiResponse(responseCode = "403", description = "Permission denied for this contract"),
            @ApiResponse(responseCode = "404", description = "Contract does not exist"),
            @ApiResponse(responseCode = "400", description = "Contract has no PDF")
    })
    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasRole('TENANT')")
    public com.isums.contractservice.domains.dtos.ApiResponse<String> getContractPdf(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Contract ID", required = true)
            @PathVariable UUID id) {

        UUID keycloakId = UUID.fromString(jwt.getSubject());
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                service.getPdfUrlForTenant(id, keycloakId), "Presigned URL valid for 30 minutes");
    }

    @PutMapping("/{contractId}/confirm-refund")
    @PreAuthorize("hasAnyRole('LANDLORD', 'MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<Object> confirmRefund(
            @PathVariable UUID contractId,
            @RequestBody @Valid ConfirmRefundRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        service.confirmRefund(contractId, req);
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(null, "Deposit refund confirmed successfully");
    }

    @PostMapping("/{contractId}/clone-for-renewal")
    @PreAuthorize("hasAnyRole('LANDLORD', 'MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<EContractDto> cloneForRenewal(
            @PathVariable UUID contractId,
            @RequestBody @Valid CloneForRenewalRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(
                service.cloneForRenewal(contractId, req, actorId(jwt), jwt.getTokenValue()),
                "Renewal contract created");
    }

    @PutMapping("/{contractId}/confirm-power-cut")
    @PreAuthorize("hasAnyRole('LANDLORD', 'MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<Void> confirmPowerCut(@PathVariable UUID contractId, @AuthenticationPrincipal Jwt jwt) {
        powerCutService.confirmPowerCut(contractId, actorId(jwt));
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(null, "Power-cut confirmed; will execute in 24 hours");
    }

    @PutMapping("/{contractId}/confirm-termination-overdue")
    @PreAuthorize("hasAnyRole('LANDLORD', 'MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<Void> confirmTerminationOverdue(
            @PathVariable UUID contractId,
            @AuthenticationPrincipal Jwt jwt) {
        terminationService.confirmTerminationOverdue(contractId, actorId(jwt));
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(null, "Contract termination process initiated");
    }

    @Operation(
            summary = "[DEBUG] VNPT token 取得テスト",
            description = "開発環境でVNPT接続を確認するためにのみ使用します。",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/test-token")
    @PreAuthorize("hasRole('LANDLORD')")
    public String testToken() {
        return vnptClient.getToken();
    }

    private UUID actorId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT subject");
        }
    }

}
