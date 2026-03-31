package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.infrastructures.abstracts.EContractService;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import common.paginations.dtos.PageRequest;
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
    private final VnptEContractClient vnptClient;

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
                "Tạo hợp đồng thành công");
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
    public com.isums.contractservice.domains.dtos.ApiResponse<EContractDto> getById(
            @Parameter(description = "契約ID", required = true) @PathVariable UUID id) {
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.getById(id), "Success");
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
//    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public com.isums.contractservice.domains.dtos.ApiResponse<PageResponse<EContractDto>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
            @RequestParam(required = false, defaultValue = "DESC") String sortDir) {

        PageRequest request = PageRequest.of(page, size)
                .withSortBy(sortBy)
                .withSortDir(sortDir);

        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.getAll(request), "Success");
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
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.updateContract(id, req), "Cập nhật thành công");
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
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(null, "Xóa hợp đồng thành công");
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
                "Đã gửi hợp đồng cho tenant xem và xác nhận");
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
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.signByLandlord(req), "Landlord đã ký thành công");
    }

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
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(null, "Đã huỷ hợp đồng");
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
                "Presigned URL hợp lệ trong 30 phút");
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
                "Xác nhận thành công. Hợp đồng đã được gửi lên hệ thống ký điện tử.");
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
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(null, "Đã từ chối hợp đồng");
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
                "Lấy thông tin ký thành công");
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
        return com.isums.contractservice.domains.dtos.ApiResponses.ok(service.signByTenant(req), "Ký hợp đồng thành công");
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
            summary = "[DEBUG] VNPT token 取得テスト",
            description = "開発環境でVNPT接続を確認するためにのみ使用します。",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/test-token")
    @PreAuthorize("hasRole('LANDLORD')")
    public String testToken() {
        return vnptClient.getToken();
    }

    @PostMapping("/test-payment")
    @PreAuthorize("hasRole('LANDLORD')")
    public void testPayment(@RequestBody UUID eContractId) {
        service.testPayment(eContractId);
    }

    private UUID actorId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT subject");
        }
    }

}