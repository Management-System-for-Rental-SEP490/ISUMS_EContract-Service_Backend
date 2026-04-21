package com.isums.contractservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.assetservice.grpc.AssetItemDto;
import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.domains.entities.*;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import com.isums.contractservice.domains.events.*;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.exceptions.OcrValidationException;
import com.isums.contractservice.infrastructures.abstracts.*;
import com.isums.contractservice.infrastructures.grpcs.*;
import com.isums.contractservice.infrastructures.mappers.EContractMapper;
import com.isums.contractservice.infrastructures.repositories.*;
import com.isums.contractservice.utils.NumberToTextConverter;
import com.isums.houseservice.grpc.HouseResponse;
import com.isums.userservice.grpc.UserResponse;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import common.paginations.cache.CachedPageService;
import common.paginations.converters.SpringPageConverter;
import common.paginations.dtos.PageRequest;
import common.paginations.dtos.PageResponse;
import common.paginations.specifications.SpecificationBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.core.type.TypeReference;

import java.io.*;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EContractServiceImpl implements EContractService {

    private final VnptEContractClient vnptClient;
    private final KafkaTemplate<String, Object> kafka;
    private final SimpMessagingTemplate ws;
    private final HouseGrpcClient houseGrpc;
    private final UserGrpcClient userGrpc;
    private final AssetGrpcClient assetGrpc;
    private final EContractRepository contractRepo;
    private final EContractTemplateRepository templateRepo;
    private final LandlordProfileRepository landlordRepo;
    private final EContractMapper mapper;
    private final S3Service s3;
    private final ObjectMapper json;
    private final ContractTokenService contractTokenService;
    private final RenewalRequestRepository renewalRequestRepo;
    private final RenewalServiceImpl renewalService;
    private final ContractCoTenantRepository coTenantRepo;
    private final ContractHtmlBuilder htmlBuilder;
    private final OutboxPublisher outboxPublisher;

    private static final String PAGE_NS = "econtracts";
    private static final Duration PAGE_TTL = Duration.ofMinutes(60);

    private final CachedPageService cachedPageService;

    @Value("${ocr.service.url}")
    private String ocrUrl;

    @Value("${vnpt.landlord.username}")
    private String vnptLandlordUsername;

    @Value("${app.contract.view-url:https://outsystem.isums.pro/contracts}")
    private String contractViewBaseUrl;

    @Value("${app.contract.pdf-url-ttl-minutes:30}")
    private int pdfUrlTtlMinutes;

    private final DateTimeFormatter DMY =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);

    private record OcrResult(String identityNumber, String fullName, String dateOfBirth, String gender,
                             String placeOfOrigin, String address, String issueDate, String issuePlace) {
        static OcrResult from(JsonNode n) {
            return new OcrResult(t(n, "identityNumber"), t(n, "fullName"),
                    t(n, "dateOfBirth"), t(n, "gender"),
                    t(n, "placeOfOrigin"), t(n, "address"),
                    t(n, "issueDate"), t(n, "issuePlace"));
        }

        private static String t(JsonNode n, String f) {
            JsonNode v = n.path(f);
            return v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
        }
    }

    @Override
    @Transactional
    public EContractDto createDraft(UUID actorId, String jwtToken, CreateEContractRequest req) {
        try {
            // Cross-field validation: FOREIGNER must supply passport + nationality,
            // VIETNAMESE must supply CCCD. Jakarta @Pattern checks format but not presence.
            if (!req.hasRequiredIdentity()) {
                throw new IllegalArgumentException(req.tenantTypeOrDefault() == com.isums.contractservice.domains.enums.TenantType.FOREIGNER
                        ? "Người thuê nước ngoài phải có số hộ chiếu và quốc tịch."
                        : "Người thuê Việt Nam phải có số CCCD.");
            }

            HouseResponse house = houseGrpc.getHouseById(req.houseId());
            if (house == null) throw new NotFoundException("House not found: " + req.houseId());

            UUID tenantId;
            if (!req.isNewAccount()) {
                tenantId = UUID.fromString(userGrpc.getUserByEmail(req.email(), jwtToken).getId());
            } else {
                tenantId = UUID.randomUUID();
                createVnptUser(vnptClient.getToken(), tenantId, req);
                kafka.send("createUser-topic", CreateUserPlacedEvent.builder()
                        .id(tenantId).name(req.name()).email(req.email())
                        .phoneNumber(req.phoneNumber()).identityNumber(req.identityNumber())
                        .isEnabled(false).build());
            }

            cachedPageService.evictAll(PAGE_NS);

            return mapper.contractToDto(buildAndSaveDraft(actorId, tenantId, req, house));

        } catch (IllegalArgumentException | IllegalStateException | NotFoundException e) {
            throw e;
        } catch (Exception ex) {
            log.error("createDraft failed", ex);
            throw new IllegalStateException("Tạo hợp đồng thất bại: " + ex.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public EContractDto getById(UUID id, org.springframework.security.core.Authentication auth) {
        EContract contract = findById(id);
        requireAccess(contract, auth);

        if (contract.getStatus() != EContractStatus.DRAFT && contract.getStatus() != EContractStatus.PENDING_TENANT_REVIEW) {

            String pdfUrl = getPdfPresignedUrlForAdmin(id);

            EContractDto dto = mapper.contractToDto(contract);
            return dto.updatePdfUrl(pdfUrl);
        }
        return mapper.contractToDto(contract);
    }

    @Override
    public PageResponse<EContractDto> getAll(PageRequest request, org.springframework.security.core.Authentication auth) {
        ContractScope scope = resolveScope(auth);
        // Cache key must vary by scope so one user's filtered list doesn't leak into another's.
        String scopedNs = PAGE_NS + ":" + scope.cacheKey();
        return cachedPageService.getOrLoad(scopedNs, request, new TypeReference<>() {
                },
                () -> loadPage(request, scope)
        );
    }

    @Override
    @Transactional
    public EContractDto updateContract(UUID id, UpdateEContractRequest req) {
        EContract c = findById(id);
        EContractStatus current = c.getStatus();

        if (current == EContractStatus.PENDING_TENANT_REVIEW) {
            c.getStatus().validateTransition(EContractStatus.CORRECTING);
            mapper.patch(req, c);
            c.setStatus(EContractStatus.CORRECTING);

            s3.deleteIfExists(c.getSnapshotKey());
            c.setSnapshotKey(null);

            contractRepo.save(c);

            sendWsStatus(c.getId(), "CORRECTING", "Hợp đồng đang được hiệu chỉnh bởi chủ nhà. Vui lòng chờ phiên bản mới.");

            log.info("[EContract] CORRECTING contractId={}", id);

            cachedPageService.evictAll(PAGE_NS);

        } else if (current == EContractStatus.DRAFT || current == EContractStatus.CORRECTING) {
            mapper.patch(req, c);
            contractRepo.save(c);

        } else {
            throw new IllegalStateException("Không thể chỉnh sửa hợp đồng ở trạng thái: " + current);
        }

        return mapper.contractToDto(c);
    }

    @Override
    @Transactional
    public EContractDto confirmByAdmin(UUID contractId, UUID actorId) {
        EContract c = findById(contractId);
        EContractStatus cur = c.getStatus();

        if (cur != EContractStatus.DRAFT && cur != EContractStatus.CORRECTING && cur != EContractStatus.PENDING_TENANT_REVIEW) {
            throw new IllegalStateException("Chỉ confirm được ở trạng thái DRAFT, PENDING_TENANT_REVIEW hoặc CORRECTING. Hiện tại: " + cur);
        }

        byte[] pdfBytes = renderHtmlToPdf(c.getHtml());

        s3.deleteIfExists(c.getSnapshotKey());
        String snapshotKey = s3.uploadContractPdf(pdfBytes, contractId);

        c.setSnapshotKey(snapshotKey);
        c.getStatus().validateTransition(EContractStatus.PENDING_TENANT_REVIEW);
        c.setStatus(EContractStatus.PENDING_TENANT_REVIEW);
        contractRepo.save(c);

        String magicToken = contractTokenService.generateToken(contractId, c.getUserId());
        String pdfViewUrl = s3.presignedUrl(snapshotKey, 24 * 60);
        String confirmUrl = contractViewBaseUrl + "/" + contractId + "/confirm?token=" + magicToken;

        ConfirmAndSendToTenantEvent event = ConfirmAndSendToTenantEvent.builder()
                .messageId(UUID.randomUUID().toString())
                .recipientUserId(c.getUserId())
                .contractId(contractId)
                .contractName(c.getName())
                .url(pdfViewUrl)
                .confirmUrl(confirmUrl)
                .startDate(c.getStartAt())
                .endDate(c.getEndAt())
                .build();

        cachedPageService.evictAll(PAGE_NS);

        // Enqueue via outbox — atomic with the status transition above. If the DB
        // commit succeeds but Kafka is down, the poller retries until sent;
        // if the tx rolls back, no phantom tenant email goes out.
        outboxPublisher.enqueue(
                "confirmAndSendToTenant-topic",
                contractId.toString(),
                event,
                event.messageId());

        log.info("[EContract] PENDING_TENANT_REVIEW contractId={} snapshotKey={} outbox enqueued",
                contractId, snapshotKey);
        return mapper.contractToDto(c);
    }

    @Transactional(readOnly = true)
    public String getPdfPresignedUrlForAdmin(UUID contractId) {
        EContract c = findById(contractId);

        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("Hợp đồng chưa được tạo PDF.");
        }

        return s3.presignedUrl(c.getSnapshotKey(), 60);
    }

    @Override
    @Transactional(readOnly = true)
    public String getPdfPresignedUrl(UUID contractId, String contractToken) {
        contractTokenService.validateToken(contractToken, contractId);
        EContract c = findById(contractId);

        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("Hợp đồng chưa được tạo PDF.");
        }

        return s3.presignedUrl(c.getSnapshotKey(), pdfUrlTtlMinutes);
    }

    @Override
    @Transactional
    public VnptDocumentDto tenantConfirmWithCccd(UUID contractId, MultipartFile frontImage, MultipartFile backImage, String contractToken) {

        contractTokenService.validateToken(contractToken, contractId);

        EContract c = findById(contractId);

        if (c.getStatus() != EContractStatus.PENDING_TENANT_REVIEW) {
            throw new IllegalStateException("Hợp đồng không ở trạng thái chờ xác nhận. Hiện tại: " + c.getStatus());
        }
        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("Hợp đồng chưa có PDF snapshot. Vui lòng liên hệ chủ nhà.");
        }

        validateImage(frontImage, "mặt trước");
        validateImage(backImage, "mặt sau");

        OcrResult ocrFront = callOcrFrontAndValidate(
                frontImage, c.getCccdNumber(), c.getTenantName());
        validateCccdBack(backImage);

        s3.deleteIfExists(c.getCccdFrontKey());
        s3.deleteIfExists(c.getCccdBackKey());
        String frontKey = s3.uploadCccdImage(frontImage, contractId, "mat-truoc");
        String backKey = s3.uploadCccdImage(backImage, contractId, "mat-sau");

        byte[] snapshotPdf = s3.downloadBytes(c.getSnapshotKey());
        byte[] cccdFront = s3.compressCccdImage(s3.downloadBytes(frontKey));
        byte[] cccdBack = s3.compressCccdImage(s3.downloadBytes(backKey));
        byte[] finalPdf = s3.appendCccdPage(snapshotPdf, cccdFront, cccdBack);

        try (PDDocument doc = Loader.loadPDF(finalPdf)) {
            log.info("[EContract] finalPdf pages={}", doc.getNumberOfPages());
        } catch (IOException _) {
        }

        Map<String, AnchorBoxVnpt> anchors = findAnchors(finalPdf, List.of("SIGN_A", "SIGN_B"));
        VnptPosition posA = getVnptPosition(
                finalPdf, anchors.get("SIGN_A"), 170, 90, 60, 18, -28, 35, 20, 60);
        VnptPosition posB = getVnptPosition(
                finalPdf, anchors.get("SIGN_B"), 170, 90, 60, 18, 0, 35, 20, 60);

        log.info("[EContract] finalPdf size={}KB contractId={}", finalPdf.length / 1024, contractId);

        String token = vnptClient.getToken();
        VnptDocumentDto document = ensureVnptDocument(c, finalPdf, token);
        String documentId = document.id();

        c.setDocumentId(documentId);
        c.setDocumentNo(document.no());
        c.setCccdFrontKey(frontKey);
        c.setCccdBackKey(backKey);
        c.setCccdVerifiedAt(Instant.now());
        if (c.getStatus() != EContractStatus.READY) {
            c.getStatus().validateTransition(EContractStatus.READY);
            c.setStatus(EContractStatus.READY);
        }
        contractRepo.save(c);

        updateProcess(token, documentId,
                vnptLandlordUsername,
                c.getUserId().toString(),
                posA.pos(),
                posB.pos(),
                posA.pageSign(),
                posB.pageSign());

        VnptResult<VnptDocumentDto> sendResult = vnptClient.sendProcess(token, documentId);
        if (sendResult == null || sendResult.getData() == null) {
            c.setStatus(EContractStatus.PENDING_TENANT_REVIEW);
            contractRepo.save(c);
            throw new IllegalStateException("VNPT sendProcess thất bại: "
                    + (sendResult != null ? sendResult.getMessage() : "null"));
        }

        log.info("[EContract] READY contractId={} documentId={} cccdId={}",
                contractId, documentId,
                ocrFront != null ? ocrFront.identityNumber() : "ocr-skipped");

        contractTokenService.invalidateToken(contractToken);

        cachedPageService.evictAll(PAGE_NS);
        sendReadyForLandlordSignatureEvent(c);

        return sendResult.getData();
    }

    @Override
    @Transactional
    public VnptDocumentDto tenantConfirmWithPassport(UUID contractId, MultipartFile passportImage, String contractToken) {

        contractTokenService.validateToken(contractToken, contractId);

        EContract c = findById(contractId);

        if (c.getStatus() != EContractStatus.PENDING_TENANT_REVIEW) {
            throw new IllegalStateException("Hợp đồng không ở trạng thái chờ xác nhận. Hiện tại: " + c.getStatus());
        }
        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("Hợp đồng chưa có PDF snapshot. Vui lòng liên hệ chủ nhà.");
        }

        validateImage(passportImage, "hộ chiếu");

        PassportIdentityDto ocrPassport = callOcrPassportAndValidate(
                passportImage, c.getPassportNumber(), c.getTenantName(), c.getNationality());

        s3.deleteIfExists(c.getPassportFrontKey());
        String passportKey = s3.uploadPassportImage(passportImage, contractId);

        byte[] snapshotPdf = s3.downloadBytes(c.getSnapshotKey());
        byte[] passportBytes = s3.compressCccdImage(s3.downloadBytes(passportKey));
        byte[] finalPdf = s3.appendPassportPage(snapshotPdf, passportBytes);

        try (PDDocument doc = Loader.loadPDF(finalPdf)) {
            log.info("[EContract] finalPdf pages={} (passport flow)", doc.getNumberOfPages());
        } catch (IOException _) {
        }

        Map<String, AnchorBoxVnpt> anchors = findAnchors(finalPdf, List.of("SIGN_A", "SIGN_B"));
        VnptPosition posA = getVnptPosition(
                finalPdf, anchors.get("SIGN_A"), 170, 90, 60, 18, -28, 35, 20, 60);
        VnptPosition posB = getVnptPosition(
                finalPdf, anchors.get("SIGN_B"), 170, 90, 60, 18, 0, 35, 20, 60);

        log.info("[EContract] finalPdf size={}KB contractId={} (passport)", finalPdf.length / 1024, contractId);

        String token = vnptClient.getToken();
        VnptDocumentDto document = ensureVnptDocument(c, finalPdf, token);
        String documentId = document.id();

        c.setDocumentId(documentId);
        c.setDocumentNo(document.no());
        c.setPassportFrontKey(passportKey);
        c.setPassportVerifiedAt(Instant.now());
        if (c.getStatus() != EContractStatus.READY) {
            c.getStatus().validateTransition(EContractStatus.READY);
            c.setStatus(EContractStatus.READY);
        }
        contractRepo.save(c);

        updateProcess(token, documentId,
                vnptLandlordUsername,
                c.getUserId().toString(),
                posA.pos(),
                posB.pos(),
                posA.pageSign(),
                posB.pageSign());

        VnptResult<VnptDocumentDto> sendResult = vnptClient.sendProcess(token, documentId);
        if (sendResult == null || sendResult.getData() == null) {
            c.setStatus(EContractStatus.PENDING_TENANT_REVIEW);
            contractRepo.save(c);
            throw new IllegalStateException("VNPT sendProcess thất bại: "
                    + (sendResult != null ? sendResult.getMessage() : "null"));
        }

        log.info("[EContract] READY contractId={} documentId={} passport={}",
                contractId, documentId,
                ocrPassport != null ? ocrPassport.getPassportNumber() : "ocr-skipped");

        contractTokenService.invalidateToken(contractToken);

        cachedPageService.evictAll(PAGE_NS);
        sendReadyForLandlordSignatureEvent(c);

        return sendResult.getData();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPassport(UUID contractId) {
        EContract c = findById(contractId);
        return c.getPassportFrontKey() != null;
    }

    @Override
    @Transactional(readOnly = true)
    public void triggerReadyForLandlordSignatureNotification(UUID contractId) {
        EContract contract = findById(contractId);

        if (contract.getStatus() != EContractStatus.READY) {
            throw new IllegalStateException(
                    "Chỉ replay notification khi hợp đồng đang ở READY. Hiện tại: " + contract.getStatus());
        }
        if (contract.getCreatedBy() == null) {
            throw new IllegalStateException(
                    "Hợp đồng thiếu createdBy nên không xác định được người nhận notification.");
        }

        sendReadyForLandlordSignatureEvent(contract);
    }

    @Override
    @Transactional
    public ProcessResponse signByLandlord(VnptProcessDto process) {
        VnptProcessDto withToken = process.withToken(vnptClient.getToken());
        VnptResult<ProcessResponse> result = vnptClient.signProcess(withToken);

        if (result == null || result.getData() == null) {
            throw new IllegalStateException("Landlord ký thất bại: "
                    + (result != null ? result.getMessage() : "null"));
        }

        if (result.getSuccess() && result.getData().id() != null) {
            EContract c = findByDocumentId(String.valueOf(result.getData().id()));
            c.getStatus().validateTransition(EContractStatus.IN_PROGRESS);
            c.setStatus(EContractStatus.IN_PROGRESS);
            contractRepo.save(c);
            log.info("[EContract] IN_PROGRESS (landlord signed) contractId={}", c.getId());
            updateSnapshotFromVnpt(c);
        }

        cachedPageService.evictAll(PAGE_NS);

        return result.getData();
    }

    @Override
    public ProcessLoginInfoDto getAccessInfoByProcessCode(String processCode) {
        try {
            ProcessLoginInfoDto result = parseProcessLogin(vnptClient.getAccessInfoByProcessCode(processCode));
            EContract contract = contractRepo.findByDocumentId(result.documentId()).orElseThrow(() -> new NotFoundException("EContract not found"));
            if (contract.getSnapshotKey() == null) {
                throw new IllegalStateException("Hợp đồng chưa được tạo PDF.");
            }

            String pdfUrl = s3.presignedUrl(contract.getSnapshotKey(), pdfUrlTtlMinutes);
            log.info(pdfUrl);

            return result.updatePdfUrl(pdfUrl);
        } catch (Exception ex) {
            log.error("getAccessInfoByProcessCode failed processCode={}", processCode, ex);
            throw new IllegalStateException("Lấy thông tin ký thất bại: " + ex.getMessage());
        }
    }


    @Override
    @Transactional
    @CacheEvict(cacheNames = "vnptProcessCode", key = "#process.processCode")
    public ProcessResponse signByTenant(VnptProcessDto process) {
        VnptResult<ProcessResponse> result = vnptClient.signProcess(process);

        if (result == null || result.getData() == null) {
            throw new IllegalStateException("Tenant ký thất bại: "
                    + (result != null ? result.getMessage() : "null"));
        }

        if (result.getSuccess() && result.getData().id() != null) {
            EContract c = findByDocumentId(String.valueOf(result.getData().id()));
            c.getStatus().validateTransition(EContractStatus.COMPLETED);
            c.setStatus(EContractStatus.COMPLETED);
            contractRepo.save(c);
            log.info("[EContract] COMPLETED contractId={}", c.getId());

            // House mapping + Keycloak activation happen downstream in Payment-Service
            // after deposit is paid (see ContractEventListener.handleDepositPaid).
            fetchAndStoreSignedPdf(c);

            cachedPageService.evictAll(PAGE_NS);
        }

        return result.getData();
    }

    @Override
    @Transactional
    public void cancelByTenant(UUID contractId, String reason, UUID tenantUserId, String contractToken) {

        contractTokenService.validateToken(contractToken, contractId);

        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.PENDING_TENANT_REVIEW
                && c.getStatus() != EContractStatus.IN_PROGRESS
                && c.getStatus() != EContractStatus.COMPLETED) {
            throw new IllegalStateException("Tenant chỉ huỷ được ở PENDING_TENANT_REVIEW hoặc IN_PROGRESS. Hiện tại: " + c.getStatus());
        }
        c.setStatus(EContractStatus.CANCELLED_BY_TENANT);
        c.setTerminatedAt(Instant.now());
        c.setTerminatedReason(reason);
        c.setTerminatedBy(c.getUserId());
        purgePiiObjects(c);
        contractRepo.save(c);
        log.info("[EContract] CANCELLED_BY_TENANT contractId={} PII purged", contractId);

        contractTokenService.invalidateToken(contractToken);
        sendContractCancelledByTenantEvent(c);

        cachedPageService.evictAll(PAGE_NS);
    }

    @Override
    @Transactional
    public void cancelByLandlord(UUID contractId, String reason, UUID actorId) {
        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.CORRECTING
                && c.getStatus() != EContractStatus.READY) {
            throw new IllegalStateException("Landlord chỉ huỷ được ở CORRECTING hoặc READY. Hiện tại: " + c.getStatus());
        }
        c.setStatus(EContractStatus.CANCELLED_BY_LANDLORD);
        c.setTerminatedAt(Instant.now());
        c.setTerminatedReason(reason);
        c.setTerminatedBy(actorId);
        purgePiiObjects(c);
        contractRepo.save(c);
        log.info("[EContract] CANCELLED_BY_LANDLORD contractId={} PII purged", contractId);

        cachedPageService.evictAll(PAGE_NS);
    }

    @Override
    @Transactional
    @CacheEvict(allEntries = true, value = "allEContracts")
    public void deleteContract(UUID contractId, UUID actorId) {
        EContract c = findById(contractId);
        if (c.getStatus() != EContractStatus.DRAFT) {
            throw new IllegalStateException("Chỉ xóa được hợp đồng ở trạng thái DRAFT. Hiện tại: " + c.getStatus());
        }
        // Soft-delete: retain the row for legal audit (10-year retention under
        // Law on Contracts + Law on Accounting). PII is purged from S3 but the
        // structured record stays for auditors.
        purgePiiObjects(c);
        c.setDeletedAt(Instant.now());
        c.setDeletedBy(actorId);
        c.setStatus(EContractStatus.DELETED);
        contractRepo.save(c);
        log.info("[EContract] SOFT-DELETED contractId={} by={} (row retained for audit)", contractId, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCccd(UUID contractId) {
        EContract c = findById(contractId);
        return c.getCccdFrontKey() != null && c.getCccdBackKey() != null;
    }

    @Override
    public EContractDto getOutSystem(String processCode) {
        ProcessLoginInfoDto info = getAccessInfoByProcessCode(processCode);
        return mapper.contractToDto(
                contractRepo.findByDocumentNo(info.documentNo())
                        .orElseThrow(() -> new NotFoundException("EContract not found")));
    }

    @Override
    @Cacheable(cacheNames = "vnptDocumentById", key = "#documentId")
    public VnptDocumentDto getVnptDocumentById(String documentId) {
        String token = vnptClient.getToken();
        VnptResult<VnptDocumentDto> r = vnptClient.getEContractById(documentId, token);
        if (r == null || r.getData() == null || r.getData().id() == null)
            throw new NotFoundException("VNPT document not found: " + documentId);
        return r.getData();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantEContractDto> getMyContracts(UUID keycloakId) {
        UUID userId = resolveInternalTenantId(keycloakId);
        return contractRepo.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(c -> new TenantEContractDto(
                        c.getId(),
                        c.getName(),
                        c.getHouseId(),
                        c.getStartAt(),
                        c.getEndAt(),
                        c.getStatus(),
                        resolvePdfUrlForTenant(c),
                        c.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String getPdfUrlForTenant(UUID contractId, UUID keycloakId) {
        EContract c = findById(contractId);

        UUID userId = resolveInternalTenantId(keycloakId);

        if (!c.getUserId().equals(userId)) {
            throw new AccessDeniedException("You are not authorized to view this contract..");
        }
        if (c.getSnapshotKey() == null) {
            throw new IllegalStateException("The contract is not yet available in PDF format. Please contact the landlord.");
        }
        return s3.presignedUrl(c.getSnapshotKey(), 30);
    }

    @Transactional
    public void confirmRefund(UUID contractId, ConfirmRefundRequest req) {
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new RuntimeException("Contract not found: " + contractId));

        contract.getStatus().validateTransition(EContractStatus.DEPOSIT_REFUND_PENDING);
        contract.setStatus(EContractStatus.DEPOSIT_REFUND_PENDING);
        contractRepo.save(contract);

        UserResponse tenant = userGrpc.getUserById(contract.getUserId().toString());

        kafka.send("contract.deposit-refund.confirmed",
                contractId.toString(),
                DepositRefundConfirmedEvent.builder()
                        .contractId(contractId)
                        .houseId(contract.getHouseId())
                        .tenantId(contract.getUserId())
                        .tenantEmail(tenant.getEmail())
                        .refundAmount(req.refundAmount())
                        .note(req.note())
                        .messageId(UUID.randomUUID().toString())
                        .build());

        log.info("[Contract] DEPOSIT_REFUND_PENDING contractId={} refundAmount={}",
                contractId, req.refundAmount());
    }

    @Transactional
    public EContractDto cloneForRenewal(UUID oldContractId, CloneForRenewalRequest req, UUID actorId, String jwtToken) {
        EContract old = findById(oldContractId);

        UserResponse tenant = userGrpc.getUserById(old.getUserId().toString());

        CreateEContractRequest newReq = CreateEContractRequest.builder()
                .isNewAccount(false)
                .name(old.getTenantName())
                .email(tenant.getEmail())
                .houseId(old.getHouseId())
                .startDate(req.newStartDate())
                .endDate(req.newEndDate())
                .rentAmount(req.newRentAmount())
                .payDate(old.getPayDate())
                .depositAmount(old.getDepositAmount())
                .depositDate(req.newStartDate())
                .handoverDate(req.newStartDate())
                .lateDays(old.getLateDays())
                .latePenaltyPercent(old.getLatePenaltyPercent())
                .depositRefundDays(old.getDepositRefundDays())
                .renewNoticeDays(old.getRenewNoticeDays() != null
                        ? old.getRenewNoticeDays() : 30)
                .build();

        EContractDto newContract = createDraft(actorId, jwtToken, newReq);

        if (req.renewalRequestId() != null) {
            renewalService.markNewContractDrafted(req.renewalRequestId(), newContract.id());
        }

        return newContract;
    }

    private void sendWsStatus(UUID contractId, String status, String message) {
        try {
            Map<String, Object> payload = Map.of(
                    "contractId", contractId.toString(),
                    "status", status,
                    "message", message);
            ws.convertAndSend("/topic/contract/" + contractId + "/status", (Object) payload);
        } catch (Exception e) {
            log.warn("[WS] Send failed contractId={}: {}", contractId, e.getMessage());
        }
    }

    private OcrResult callOcrFrontAndValidate(MultipartFile file, String expectedId, String expectedName) {
        try {
            JsonNode node = callOcr(file, "/ocr/cccd");
            OcrResult result = OcrResult.from(node);

            if (!node.path("isFrontSide").asBoolean(false))
                throw new OcrValidationException(OcrValidationException.NOT_FRONT_SIDE, "Ảnh không phải mặt trước CCCD.");

            if (result.identityNumber() == null)
                throw new OcrValidationException(OcrValidationException.CANNOT_READ_ID, "Không đọc được số CCCD.");

            if (expectedId != null && !expectedId.isBlank()
                    && !result.identityNumber().equals(expectedId))
                throw new OcrValidationException(OcrValidationException.ID_MISMATCH, "Số CCCD không khớp hợp đồng.");

            if (result.fullName() != null && expectedName != null && !expectedName.isBlank()) {
                String normOcr = norm(result.fullName());
                String normExpected = norm(expectedName);
                if (!normOcr.equals(normExpected) && !normExpected.contains(normOcr) && !normOcr.contains(normExpected)) {
                    throw new OcrValidationException(OcrValidationException.NAME_MISMATCH, "Tên không khớp hợp đồng.");
                }
            } else if (result.fullName() == null) {
                log.warn("[OCR] Không đọc được tên, bỏ qua kiểm tra tên. id={}", result.identityNumber());
            }

            log.info("[OCR] Front OK id={} name={}", result.identityNumber(), result.fullName());
            return result;
        } catch (OcrValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OCR] Front service lỗi, bỏ qua validate: {}", e.getMessage());
            return null;
        }
    }

    private void validateCccdBack(MultipartFile file) {
        try {
            JsonNode node = callOcr(file, "/ocr/cccd/back");

            if (!node.path("isReadable").asBoolean(true))
                throw new OcrValidationException(OcrValidationException.IMAGE_NOT_READABLE, "Ảnh mặt sau không rõ nét.");

            if (!node.path("isBackSide").asBoolean(false))
                throw new OcrValidationException(OcrValidationException.NOT_BACK_SIDE, "Ảnh không phải mặt sau CCCD.");

        } catch (OcrValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OCR] Back service lỗi, bỏ qua validate: {}", e.getMessage());
        }
    }

    private PassportIdentityDto callOcrPassportAndValidate(
            MultipartFile file, String expectedPassportNumber, String expectedName, String expectedNationality) {
        try {
            JsonNode node = callOcr(file, "/ocr/passport");
            PassportIdentityDto result = json.treeToValue(node, PassportIdentityDto.class);

            if (result == null || result.getPassportNumber() == null)
                throw new OcrValidationException(OcrValidationException.CANNOT_READ_PASSPORT,
                        "Không đọc được số hộ chiếu từ MRZ. Vui lòng chụp rõ trang thông tin.");

            if (expectedPassportNumber != null && !expectedPassportNumber.isBlank()
                    && !result.getPassportNumber().equalsIgnoreCase(expectedPassportNumber)) {
                throw new OcrValidationException(OcrValidationException.PASSPORT_MISMATCH,
                        "Số hộ chiếu không khớp hợp đồng.");
            }

            if (result.getExpiryDate() != null && !result.getExpiryDate().isBlank()) {
                try {
                    java.time.LocalDate expiry = java.time.LocalDate.parse(result.getExpiryDate());
                    if (expiry.isBefore(java.time.LocalDate.now())) {
                        throw new OcrValidationException(OcrValidationException.PASSPORT_EXPIRED,
                                "Hộ chiếu đã hết hạn ngày " + expiry + ".");
                    }
                } catch (java.time.format.DateTimeParseException ignored) {
                }
            }

            String ocrName = result.getFullName();
            if (ocrName == null) {
                ocrName = joinName(result.getGivenName(), result.getSurname());
            }
            if (ocrName != null && expectedName != null && !expectedName.isBlank()) {
                String normOcr = norm(ocrName);
                String normExpected = norm(expectedName);
                if (!normOcr.equals(normExpected) && !normExpected.contains(normOcr) && !normOcr.contains(normExpected)) {
                    throw new OcrValidationException(OcrValidationException.NAME_MISMATCH,
                            "Tên trên hộ chiếu không khớp hợp đồng.");
                }
            }

            if (expectedNationality != null && !expectedNationality.isBlank()) {
                String ocrNat = result.getNationality();
                String ocrCode = result.getCountryCode();
                boolean natMatch = ocrNat != null && norm(ocrNat).equals(norm(expectedNationality));
                boolean codeMatch = ocrCode != null && ocrCode.equalsIgnoreCase(expectedNationality);
                if (!natMatch && !codeMatch) {
                    log.warn("[OCR] Nationality mismatch expected={} ocr={} code={}",
                            expectedNationality, ocrNat, ocrCode);
                }
            }

            log.info("[OCR] Passport OK number={} name={} nationality={}",
                    result.getPassportNumber(), ocrName, result.getNationality());
            return result;
        } catch (OcrValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OCR] Passport service lỗi, bỏ qua validate: {}", e.getMessage());
            return null;
        }
    }

    private String joinName(String given, String surname) {
        if (given == null && surname == null) return null;
        if (given == null) return surname;
        if (surname == null) return given;
        return (surname + " " + given).trim();
    }

    private JsonNode callOcr(MultipartFile file, String path) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        });
        String raw = RestClient.create().post()
                .uri(ocrUrl + path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve().body(String.class);
        return json.readTree(raw);
    }

    private void validateImage(MultipartFile f, String side) {
        if (f == null || f.isEmpty())
            throw new IllegalArgumentException("Ảnh " + side + " không được để trống.");
        if (f.getContentType() == null || !f.getContentType().startsWith("image/"))
            throw new IllegalArgumentException("File " + side + " phải là ảnh.");
        if (f.getSize() < 50 * 1024)
            throw new IllegalArgumentException("Ảnh " + side + " quá nhỏ (< 50KB).");
        if (f.getSize() > 10 * 1024 * 1024)
            throw new IllegalArgumentException("Ảnh " + side + " quá lớn (> 10MB).");
    }

    private String norm(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase()
                .replace("đ", "d")
                .replace("ư", "u")
                .replace("ơ", "o");
        String nfd = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void updateProcess(String token, String documentId, String userFirst, String userSecond,
                               String posA, String posB, int pageA, int pageB) {
        vnptClient.UpdateProcess(token, new VnptUpdateProcessDTO(documentId, true, List.of(
                new ProcessesRequestDTO(1, userFirst, "E", posA, pageA),
                new ProcessesRequestDTO(2, userSecond, "E", posB, pageB)
        )));
    }

    private void createVnptUser(String token, UUID id, CreateEContractRequest req) {
        VnptResult<List<VnptUserDto>> r = vnptClient.CreateOrUpdateUser(token,
                new VnptUserUpsert(id.toString(), req.email(), req.name(), req.email(),
                        req.phoneNumber(), 1, 0, 2, true, true, 1, -1,
                        new ArrayList<>(List.of(3110)),
                        new ArrayList<>(List.of(UUID.fromString("0aa2afc9-39c5-4652-baec-08ddc28cdda2")))));
        if (r == null || r.getData() == null)
            throw new IllegalStateException("Tạo user VNPT thất bại: "
                    + (r != null ? r.getMessage() : "null"));
    }

    private EContract buildAndSaveDraft(UUID actorId, UUID tenantId,
                                        CreateEContractRequest req, HouseResponse house) {
        LandlordProfile lp = landlordRepo.findByUserId(actorId)
                .orElseThrow(() -> new IllegalStateException(
                        "Landlord chưa cập nhật thông tin. " + "Vui lòng cập nhật tại PUT /api/econtracts/landlord-profiles/me."));

        Map<String, Object> meters = req.meterReadingsStart() != null
                ? req.meterReadingsStart().asMap() : Map.of();

        ContractHtmlBuilder.BuildInput input = new ContractHtmlBuilder.BuildInput(
                req, lp, house.getAddress(),
                house.getAreaM2(),                // empty "" if house profile hasn't filled it
                house.getStructure(),             // empty "" if not set
                house.getLandCertNumber(),        // empty "" if not set
                house.getLandCertIssueDate(),     // empty "" if not set
                house.getLandCertIssuer(),        // empty "" if not set
                buildAssetTable(req.houseId()),
                meters, req.contractLanguageOrDefault(), null);

        String html = htmlBuilder.build(input);

        UUID regionId = null;
        try {
            if (house.getRegionId() != null && !house.getRegionId().isBlank()) {
                regionId = UUID.fromString(house.getRegionId());
            }
        } catch (IllegalArgumentException ex) {
            log.warn("[EContract] Invalid regionId from house gRPC: '{}' — contract will be region-unscoped",
                    house.getRegionId());
        }

        EContract e = EContract.builder()
                .userId(tenantId).houseId(req.houseId())
                .regionId(regionId)
                .startAt(req.startDate()).endAt(req.endDate())
                .name("EContract_" + req.name().trim() + "_" + System.currentTimeMillis())
                .html(html)
                .status(EContractStatus.DRAFT)
                .rentAmount(req.rentAmount()).depositAmount(req.depositAmount())
                .payDate(req.payDate()).lateDays(req.lateDaysOrDefault())
                .latePenaltyPercent(req.latePenaltyPercentOrDefault())
                .depositRefundDays(req.depositRefundDaysOrDefault())
                .renewNoticeDays(req.renewNoticeDaysOrDefault())
                .handoverDate(req.handoverDate())
                .cccdNumber(req.identityNumber())
                .tenantType(req.tenantTypeOrDefault())
                .contractLanguage(req.contractLanguageOrDefault())
                .passportNumber(req.passportNumber())
                .passportIssueDate(req.passportIssueDate())
                .passportIssuePlace(req.passportIssuePlace())
                .passportExpiryDate(req.passportExpiryDate())
                .visaType(req.visaType())
                .visaExpiryDate(req.visaExpiryDate())
                .nationality(req.nationality())
                .dateOfBirth(req.dateOfBirth())
                .gender(req.gender())
                .occupation(req.occupation())
                .permanentAddress(req.permanentAddress())
                .detailedAddress(req.detailedAddress() != null ? req.detailedAddress().asMap() : null)
                .petPolicy(req.petPolicyOrDefault())
                .smokingPolicy(req.smokingPolicyOrDefault())
                .subleasePolicy(req.subleasePolicyOrDefault())
                .visitorPolicy(req.visitorPolicyOrDefault())
                .tempResidenceRegisterBy(req.tempResidenceRegisterByOrDefault())
                .taxResponsibility(req.taxResponsibilityOrDefault())
                .meterReadingsStart(meters.isEmpty() ? null : new HashMap<>(meters))
                .hasPowerCutClause(req.hasPowerCutClause() != null ? req.hasPowerCutClause() : false)
                .tenantName(req.name()).createdBy(actorId).build();
        contractRepo.save(e);
        log.info("[EContract] Created DRAFT contractId={} language={} tenantType={}",
                e.getId(), e.getContractLanguage(), e.getTenantType());

        if (req.coTenants() != null && !req.coTenants().isEmpty()) {
            List<ContractCoTenant> coTenants = req.coTenants().stream()
                    .map(dto -> ContractCoTenant.builder()
                            .contractId(e.getId())
                            .fullName(dto.fullName())
                            .identityNumber(dto.identityNumber())
                            .identityType(dto.identityType())
                            .dateOfBirth(dto.dateOfBirth())
                            .gender(dto.gender())
                            .nationality(dto.nationality())
                            .relationship(dto.relationship())
                            .phoneNumber(dto.phoneNumber())
                            .build())
                    .toList();
            coTenantRepo.saveAll(coTenants);
            log.info("[EContract] Saved {} co-tenants for contractId={}", coTenants.size(), e.getId());
        }

        return e;
    }

    private byte[] renderHtmlToPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String baseUri = Objects.requireNonNull(getClass().getResource("/")).toExternalForm();
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_1_A);
            b.useFont(() -> cp("fonts/SVN-Times New Roman 2.ttf"),
                    "Times New Roman", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> cp("fonts/SVN-Times New Roman 2 bold.ttf"),
                    "Times New Roman", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> cp("fonts/SVN-Times New Roman 2 italic.ttf"),
                    "Times New Roman", 400, BaseRendererBuilder.FontStyle.ITALIC, true);
            b.useFont(() -> cp("fonts/SVN-Times New Roman 2 bold italic.ttf"),
                    "Times New Roman", 700, BaseRendererBuilder.FontStyle.ITALIC, true);
            b.withHtmlContent(toXhtml(html, baseUri), baseUri);
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Render PDF thất bại: " + e.getMessage(), e);
        }
    }

    private String toXhtml(String html, String baseUri) {
        Document doc = Jsoup.parse(html == null ? "" : html, baseUri);
        if (doc.head().selectFirst("meta[charset]") == null)
            doc.head().prependElement("meta").attr("charset", "UTF-8");
        if (doc.head().selectFirst("base[href]") == null)
            doc.head().prependElement("base").attr("href", baseUri);
        doc.outputSettings().charset("UTF-8")
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml);
        return doc.html();
    }

    private InputStream cp(String p) {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(p);
        if (in == null) throw new IllegalStateException("Missing classpath: " + p);
        return in;
    }

    private String buildAssetTable(UUID houseId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table style=\"width:100%;border-collapse:collapse;\">")
                .append("<thead><tr>")
                .append("<th style=\"border:1px solid #000;padding:6px;width:10%;\">STT</th>")
                .append("<th style=\"border:1px solid #000;padding:6px;\">Tên tài sản</th>")
                .append("<th style=\"border:1px solid #000;padding:6px;width:15%;\">Số lượng</th>")
                .append("</tr></thead><tbody>");
        int i = 1;
        for (AssetItemDto item : assetGrpc.getAssetItemsByHouseId(houseId)) {
            sb.append("<tr>")
                    .append("<td style=\"border:1px solid #000;padding:6px;text-align:right;\">").append(i++).append("</td>")
                    .append("<td style=\"border:1px solid #000;padding:6px;\">")
                    .append(HtmlUtils.htmlEscape(item.getDisplayName())).append("</td>")
                    .append("<td style=\"border:1px solid #000;padding:6px;text-align:right;\">1</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private String renderPlaceholders(String tmpl, Map<String, Object> data) {
        Matcher m = Pattern.compile("\\{\\{\\s*([A-Z0-9_][A-Z0-9_.]*)\\s*}}").matcher(tmpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object v = data.get(key);
            if (v == null) throw new IllegalStateException("Missing placeholder: " + key);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Map<String, AnchorBoxVnpt> findAnchors(byte[] pdf, List<String> texts) {
        Set<String> remain = new LinkedHashSet<>(texts);
        Map<String, AnchorBoxVnpt> found = new HashMap<>();
        int kt = Math.max(256, texts.stream().mapToInt(String::length).max().orElse(32) * 8);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (int pi = 0; pi < doc.getNumberOfPages() && !remain.isEmpty(); pi++) {
                PDRectangle box = pageBox(doc.getPage(pi));
                AnchorStripper s = new AnchorStripper(remain, found, box.getHeight(), pi + 1, kt);
                s.setSortByPosition(true);
                s.setSuppressDuplicateOverlappingText(false);
                s.setStartPage(pi + 1);
                s.setEndPage(pi + 1);
                s.getText(doc);
                remain.removeAll(found.keySet());
            }
            if (!remain.isEmpty()) throw new IllegalStateException("Anchors not found: " + remain);
            return found;
        } catch (IOException e) {
            throw new IllegalStateException("PDF parse failed", e);
        }
    }

    private PDRectangle pageBox(PDPage p) {
        return p.getCropBox() != null ? p.getCropBox() : p.getMediaBox();
    }

    private static final class AnchorStripper extends PDFTextStripper {
        private final Set<String> remain;
        private final Map<String, AnchorBoxVnpt> found;
        private final float ph;
        private final int pn;
        private final int kt;
        private final StringBuilder buf = new StringBuilder(512);
        private final ArrayList<TextPosition> pos = new ArrayList<>(512);

        AnchorStripper(Set<String> r, Map<String, AnchorBoxVnpt> f, float ph, int pn, int kt) throws IOException {
            super();
            remain = r;
            found = f;
            this.ph = ph;
            this.pn = pn;
            this.kt = kt;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (remain.isEmpty()) return;
            buf.append(text);
            pos.addAll(positions);
            if (buf.length() > kt * 2) {
                buf.delete(0, buf.length() - kt);
                if (pos.size() > kt) pos.subList(0, pos.size() - kt).clear();
            }
            String s = buf.toString();
            for (String a : new ArrayList<>(remain)) {
                int idx = s.lastIndexOf(a);
                if (idx < 0) continue;
                int pi2 = idx - (s.length() - pos.size());
                if (pi2 < 0 || pi2 >= pos.size()) continue;
                List<TextPosition> ap = pos.subList(pi2, Math.min(pi2 + a.length(), pos.size()));
                if (ap.isEmpty()) continue;
                float xMin = Float.MAX_VALUE, yMax = 0f;
                for (TextPosition tp : ap) {
                    float x = tp.getXDirAdj();
                    if (x < xMin) xMin = x;
                    float y = tp.getYDirAdj() + tp.getHeightDir();
                    if (y > yMax) yMax = y;
                }
                found.put(a, new AnchorBoxVnpt(pn, xMin, ph - yMax));
            }
        }
    }

    public VnptPosition getVnptPosition(byte[] pdf, AnchorBoxVnpt anchor,
                                        double w, double h, double oY, double mg, double xAdj, double yAdj, double es, double tp) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int last = doc.getNumberOfPages();
            PDRectangle box = pageBox(doc.getPage(anchor.page() - 1));
            double pw = box.getWidth(), ph = box.getHeight();
            double lly = anchor.bottom() - oY + yAdj - es;
            if (lly - h < mg) {
                if (anchor.page() >= last) {
                    lly = mg;
                } else {
                    PDRectangle nb = pageBox(doc.getPage(anchor.page()));
                    return new VnptPosition(
                            buildPos(clamp(anchor.left() + xAdj, mg, nb.getWidth() - mg - w),
                                    clamp(nb.getHeight() - mg - h - tp + yAdj, mg, nb.getHeight() - mg - h), w, h),
                            anchor.page() + 1);
                }
            }
            return new VnptPosition(buildPos(clamp(anchor.left() + xAdj, mg, pw - mg - w), mg, w, h), anchor.page());
        } catch (IOException e) {
            throw new IllegalStateException("PDF read failed", e);
        }
    }

    private double clamp(double v, double min, double max) {
        return max < min ? min : Math.max(min, Math.min(max, v));
    }

    private String buildPos(double llx, double lly, double w, double h) {
        return (int) Math.round(llx) + "," + (int) Math.round(lly) + "," + (int) Math.round(llx + w) + "," + (int) Math.round(lly + h);
    }

    private ProcessLoginInfoDto parseProcessLogin(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode d = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
            String token = null;
            JsonNode tn = d.get("token");
            if (tn != null && !tn.isNull())
                token = tn.isTextual() ? tn.asText()
                        : Optional.ofNullable(tn.get("accessToken")).map(JsonNode::asText).orElse(null);
            JsonNode doc = d.get("document");
            if (doc == null || !doc.isObject())
                throw new IllegalStateException("Missing 'document': " + body);
            String docId = tx(doc, "id"), docNo = tx(doc, "no"), waitId = null, pos = null;
            Integer pBy = null, ps = null;
            boolean isOtp = false;
            JsonNode w = doc.get("waitingProcess");
            if (w != null && w.isObject()) {
                waitId = tx(w, "id");
                pBy = in(w, "processedByUserId");
                pos = tx(w, "position");
                ps = in(w, "pageSign");
                JsonNode ap = w.get("accessPermission");
                if (ap != null && ap.isObject()) {
                    JsonNode v = ap.get("value");
                    isOtp = v != null && v.canConvertToInt() && v.asInt() == 7;
                }
            }
            return new ProcessLoginInfoDto(waitId, docId, docNo, pBy, token, pos, ps, null);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse VNPT response: " + e.getMessage() + "\nRAW=" + body, e);
        }
    }

    private void sendContractCompletedEvent(EContract contract, String signedPdfUrl) {
        try {
            String tenantEmail = null;
            Boolean isNewAccount = null;
            try {
                UserResponse tenant = userGrpc.getUserById(contract.getUserId().toString());
                if (tenant != null && !tenant.getEmail().isBlank()) {
                    tenantEmail = tenant.getEmail();
                    isNewAccount = !tenant.getIsEnabled();
                }
            } catch (Exception e) {
                log.warn("[EContract] Cannot fetch tenant userId={}: {}", contract.getUserId(), e.getMessage());
            }

            ContractCompletedEvent event = ContractCompletedEvent.builder()
                    .contractId(contract.getId())
                    .tenantId(contract.getUserId())
                    .tenantEmail(tenantEmail)
                    .isNewAccount(isNewAccount)
                    .houseId(contract.getHouseId())
                    .landlordId(contract.getCreatedBy())
                    .depositAmount(contract.getDepositAmount())
                    .rentAmount(contract.getRentAmount())
                    .payDate(contract.getPayDate())
                    .startAt(contract.getStartAt())
                    .endAt(contract.getEndAt())
                    .completedAt(Instant.now())
                    .signedPdfUrl(signedPdfUrl)
                    .build();

            // Outbox: contract-completed drives DEPOSIT invoice creation in Payment
            // and welcome email in Notification. Losing this event = tenant stuck
            // with signed contract but no invoice to pay. Must be durable.
            String completedMsgId = UUID.randomUUID().toString();
            outboxPublisher.enqueue(
                    "contract-completed-topic",
                    contract.getId().toString(),
                    event,
                    completedMsgId);

            // Outbox: job.created schedules the CHECK_IN inspection; also critical
            // because the inspection is a hand-off moment between contract lifecycle
            // and property ops.
            String jobMsgId = UUID.randomUUID().toString();
            outboxPublisher.enqueue(
                    "job.created",
                    contract.getId().toString(),
                    JobCreatedEvent.builder()
                            .referenceId(contract.getId())
                            .houseId(contract.getHouseId())
                            .referenceType("INSPECTION")
                            .type("CHECK_IN")
                            .messageId(jobMsgId)
                            .build(),
                    jobMsgId);

            renewalRequestRepo.findByNewContractId(contract.getId()).ifPresent(r -> {
                r.setStatus(RenewalRequestStatus.COMPLETED);
                r.setResolvedAt(Instant.now());
                renewalRequestRepo.save(r);
                log.info("[Renewal] Auto-completed renewalRequestId={} newContractId={}",
                        r.getId(), contract.getId());
            });
        } catch (Exception e) {
            log.error("[EContract] sendContractCompletedEvent failed contractId={}: {}", contract.getId(), e.getMessage(), e);
        }
    }

    private void sendContractCancelledByTenantEvent(EContract contract) {
        try {
            ContractCancelledByTenantEvent event = ContractCancelledByTenantEvent.builder()
                    .messageId(UUID.randomUUID().toString())
                    .contractId(contract.getId())
                    .houseId(contract.getHouseId())
                    .tenantId(contract.getUserId())
                    .tenantName(contract.getTenantName())
                    .reason(contract.getTerminatedReason())
                    .cancelledAt(contract.getTerminatedAt())
                    .initiatedByUserId(contract.getCreatedBy())
                    .build();

            kafka.send("contract.cancelled-by-tenant", contract.getId().toString(), event)
                    .whenComplete((res, ex) -> {
                        if (ex != null) {
                            log.error("[EContract] Kafka FAILED cancelled-by-tenant contractId={}: {}",
                                    contract.getId(), ex.getMessage(), ex);
                        } else {
                            log.info("[EContract] Kafka OK cancelled-by-tenant contractId={}", contract.getId());
                        }
                    });
        } catch (Exception e) {
            log.error("[EContract] sendContractCancelledByTenantEvent failed contractId={}: {}",
                    contract.getId(), e.getMessage(), e);
        }
    }

    private void sendReadyForLandlordSignatureEvent(EContract contract) {
        if (contract.getCreatedBy() == null) {
            log.warn("[EContract] Skip ready notification because createdBy is null contractId={}", contract.getId());
            return;
        }

        try {
            ContractReadyForLandlordSignatureEvent event = ContractReadyForLandlordSignatureEvent.builder()
                    .messageId(UUID.randomUUID().toString())
                    .contractId(contract.getId())
                    .recipientUserId(contract.getCreatedBy())
                    .tenantId(contract.getUserId())
                    .tenantName(contract.getTenantName())
                    .contractName(contract.getName())
                    .documentId(contract.getDocumentId())
                    .build();

            // Outbox: landlord must know tenant confirmed. Losing = tenant sits waiting.
            outboxPublisher.enqueue(
                    "contract.ready-for-landlord-signature",
                    contract.getId().toString(),
                    event,
                    event.messageId());
        } catch (Exception e) {
            log.error("[EContract] sendReadyForLandlordSignatureEvent failed contractId={}: {}",
                    contract.getId(), e.getMessage(), e);
        }
    }

    private void updateSnapshotFromVnpt(EContract contract) {
        try {
            VnptResult<VnptDocumentDto> docResult = vnptClient.getEContractById(
                    contract.getDocumentId(), vnptClient.getToken());

            if (docResult == null || docResult.getData() == null
                    || docResult.getData().downloadUrl() == null) {
                log.warn("[EContract] No downloadUrl from VNPT contractId={} — skip snapshot update",
                        contract.getId());
                return;
            }

            byte[] signedPdf = vnptClient.downloadSignedPdf(docResult.getData().downloadUrl());

            if (signedPdf == null || signedPdf.length == 0) {
                log.warn("[EContract] Downloaded PDF empty contractId={}", contract.getId());
                return;
            }

            s3.deleteIfExists(contract.getSnapshotKey());
            String newKey = s3.uploadContractPdf(signedPdf, contract.getId());
            contract.setSnapshotKey(newKey);
            contractRepo.save(contract);

            log.info("[EContract] Snapshot updated contractId={} key={} size={}KB",
                    contract.getId(), newKey, signedPdf.length / 1024);

        } catch (Exception e) {
            log.error("[EContract] updateSnapshotFromVnpt failed contractId={}: {}",
                    contract.getId(), e.getMessage(), e);
        }
    }

    private void fetchAndStoreSignedPdf(EContract contract) {
        try {
            VnptResult<VnptDocumentDto> docResult = vnptClient.getEContractById(
                    contract.getDocumentId(), vnptClient.getToken());

            if (docResult == null || docResult.getData() == null || docResult.getData().downloadUrl() == null) {
                log.warn("[EContract] No downloadUrl contractId={} — sending event without PDF",
                        contract.getId());
                sendContractCompletedEvent(contract, null);
                return;
            }

            byte[] signedPdf = vnptClient.downloadSignedPdf(docResult.getData().downloadUrl());

            if (signedPdf == null || signedPdf.length == 0) {
                log.warn("[EContract] PDF empty contractId={}", contract.getId());
                sendContractCompletedEvent(contract, null);
                return;
            }

            s3.deleteIfExists(contract.getSnapshotKey());
            String signedKey = s3.uploadContractPdf(signedPdf, contract.getId());
            contract.setSnapshotKey(signedKey);
            contractRepo.save(contract);

            log.info("[EContract] Signed PDF stored contractId={} key={} size={}KB",
                    contract.getId(), signedKey, signedPdf.length / 1024);

            String pdfUrl = s3.presignedUrl(signedKey, 7 * 24 * 60);
            sendContractCompletedEvent(contract, pdfUrl);

        } catch (Exception e) {
            log.error("[EContract] fetchAndStoreSignedPdf failed contractId={}: {}",
                    contract.getId(), e.getMessage(), e);
            sendContractCompletedEvent(contract, null);
        }
    }

    /**
     * Role + region-aware access scope for contract queries.
     * LANDLORD sees everything. MANAGER sees contracts whose house is in their managed
     * regions (resolved via house-service gRPC). TENANT sees only their own. STAFF is
     * excluded from contract listing (they work with issue tickets instead).
     */
    private record ContractScope(
            Scope kind,
            UUID actorId,
            java.util.Set<UUID> managedHouseIds
    ) {
        enum Scope { LANDLORD_ALL, MANAGER_REGION, TENANT_OWN, DENIED }

        String cacheKey() {
            return switch (kind) {
                case LANDLORD_ALL -> "landlord";
                case MANAGER_REGION -> "mgr-" + actorId;
                case TENANT_OWN -> "tnt-" + actorId;
                case DENIED -> "deny";
            };
        }
    }

    private ContractScope resolveScope(org.springframework.security.core.Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) {
            return new ContractScope(ContractScope.Scope.DENIED, null, java.util.Set.of());
        }
        UUID actorId;
        try {
            actorId = UUID.fromString(auth.getName());
        } catch (IllegalArgumentException ex) {
            return new ContractScope(ContractScope.Scope.DENIED, null, java.util.Set.of());
        }

        boolean isLandlord = hasAuthority(auth, "ROLE_LANDLORD");
        boolean isManager = hasAuthority(auth, "ROLE_MANAGER");
        boolean isTenant = hasAuthority(auth, "ROLE_TENANT");

        if (isLandlord) {
            return new ContractScope(ContractScope.Scope.LANDLORD_ALL, actorId, java.util.Set.of());
        }
        if (isManager) {
            java.util.Set<UUID> managed;
            try {
                managed = ((HouseGrpcClient) houseGrpc).getManagedHouseIds(actorId);
            } catch (Exception ex) {
                log.warn("[Scope] Failed to resolve managed houses for managerId={}: {}", actorId, ex.getMessage());
                managed = java.util.Set.of();
            }
            return new ContractScope(ContractScope.Scope.MANAGER_REGION, actorId, managed);
        }
        if (isTenant) {
            return new ContractScope(ContractScope.Scope.TENANT_OWN, actorId, java.util.Set.of());
        }
        return new ContractScope(ContractScope.Scope.DENIED, actorId, java.util.Set.of());
    }

    private static boolean hasAuthority(org.springframework.security.core.Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private void requireAccess(EContract contract, org.springframework.security.core.Authentication auth) {
        ContractScope scope = resolveScope(auth);
        switch (scope.kind()) {
            case LANDLORD_ALL -> { /* ok */ }
            case MANAGER_REGION -> {
                if (!scope.managedHouseIds().contains(contract.getHouseId())) {
                    throw new AccessDeniedException("Contract thuộc region bạn không quản lý");
                }
            }
            case TENANT_OWN -> {
                if (!contract.getUserId().equals(scope.actorId())) {
                    throw new AccessDeniedException("Chỉ xem được hợp đồng của chính mình");
                }
            }
            case DENIED -> throw new AccessDeniedException("Không có quyền truy cập hợp đồng");
        }
    }

    private PageResponse<EContractDto> loadPage(PageRequest request, ContractScope scope) {
        EContractStatus statusFilter = request.<String>filterValue("status")
                .map(s -> {
                    try {
                        return EContractStatus.valueOf(s.toUpperCase().trim());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .orElse(null);

        String statusesRaw = request.<String>filterValue("statuses").orElse(null);

        String houseIdRaw = request.<String>filterValue("houseId").orElse(null);
        UUID houseIdFilter = houseIdRaw != null ? UUID.fromString(houseIdRaw) : null;

        var specBuilder = SpecificationBuilder.<EContract>create()
                .keywordLike(request.keyword(), "name", "tenantName")
                .enumEq("status", statusFilter)
                .enumInRaw("status", statusesRaw, EContractStatus.class)
                .eq("houseId", houseIdFilter);

        // Role-scoped filter — keep at spec layer so Postgres does the filtering,
        // not Java (avoids loading and discarding other tenants' contracts).
        switch (scope.kind()) {
            case LANDLORD_ALL -> { /* no extra filter */ }
            case MANAGER_REGION -> {
                if (scope.managedHouseIds().isEmpty()) {
                    // No managed houses → no visible contracts. Return empty fast.
                    return PageResponse.<EContractDto>empty();
                }
                specBuilder.in("houseId", scope.managedHouseIds());
            }
            case TENANT_OWN -> specBuilder.eq("userId", scope.actorId());
            case DENIED -> {
                return PageResponse.<EContractDto>empty();
            }
        }

        var spec = specBuilder.build();
        var pageable = SpringPageConverter.toPageable(request);

        Page<EContract> page = contractRepo.findAll(spec, pageable);

        return SpringPageConverter.fromPage(page, mapper::contractToDto);
    }

    private String resolvePdfUrlForTenant(EContract c) {
        if (c.getSnapshotKey() == null) return null;
        return switch (c.getStatus()) {
            case PENDING_TENANT_REVIEW,
                 READY,
                 IN_PROGRESS,
                 COMPLETED -> s3.presignedUrl(c.getSnapshotKey(), 60);
            default -> null;
        };
    }

    private String tx(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
    }

    private Integer in(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.canConvertToInt() ? v.asInt() : null;
    }

    private String nvl(String v, String fb) {
        return v != null && !v.isBlank() ? v : fb;
    }

    /**
     * Deletes all PII-bearing S3 objects tied to this contract and clears the
     * corresponding entity fields. Called on any terminal transition
     * (cancel/delete/terminate) to comply with Nghị định 13/2023/NĐ-CP
     * (data minimization — don't retain identity documents longer than needed).
     *
     * Idempotent: safe to call on a contract with null keys. Errors are logged
     * but never rethrown — we prioritize recording the state change over the
     * cleanup (which S3 lifecycle policy can sweep as a fallback).
     */
    /**
     * Idempotent VNPT document creation. If the contract already has a documentId
     * from a previous attempt (e.g. sendProcess failed last time and status
     * reverted), we verify the doc still exists at VNPT and reuse it. Only create
     * a new doc if there's no prior one — this avoids the VNPT orphan / double-billing
     * problem since VNPT does not expose a delete API.
     */
    private VnptDocumentDto ensureVnptDocument(EContract c, byte[] finalPdf, String token) {
        if (c.getDocumentId() != null && !c.getDocumentId().isBlank()) {
            try {
                VnptResult<VnptDocumentDto> r = vnptClient.getEContractById(c.getDocumentId(), token);
                if (r != null && r.getData() != null && r.getData().id() != null) {
                    log.info("[VNPT] Reusing existing documentId={} for contractId={}",
                            c.getDocumentId(), c.getId());
                    return r.getData();
                }
                log.warn("[VNPT] Prior documentId={} not found at VNPT — creating new one (orphan safe to ignore)",
                        c.getDocumentId());
            } catch (Exception ex) {
                log.warn("[VNPT] Lookup existing documentId={} failed ({}) — proceeding to create new",
                        c.getDocumentId(), ex.getMessage());
            }
        }
        String docNo = "EC_" + System.currentTimeMillis();
        VnptResult<VnptDocumentDto> createResult = vnptClient.createDocument(token, new CreateDocumentDto(
                new FileInfoDto(null, finalPdf, docNo + ".pdf"),
                "Rental EContract", "Rental EContract", 3059, 3110, docNo));
        if (createResult == null || createResult.getData() == null) {
            throw new IllegalStateException("VNPT tạo document thất bại: "
                    + (createResult != null ? createResult.getMessage() : "null"));
        }
        return createResult.getData();
    }

    private void purgePiiObjects(EContract c) {
        try {
            s3.deleteIfExists(c.getSnapshotKey());
            s3.deleteIfExists(c.getCccdFrontKey());
            s3.deleteIfExists(c.getCccdBackKey());
            s3.deleteIfExists(c.getPassportFrontKey());
            c.setSnapshotKey(null);
            c.setCccdFrontKey(null);
            c.setCccdBackKey(null);
            c.setPassportFrontKey(null);
        } catch (Exception ex) {
            log.error("[EContract] purgePii failed contractId={} — S3 lifecycle will retry: {}",
                    c.getId(), ex.getMessage());
        }
    }

    private EContract findById(UUID id) {
        return contractRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + id));
    }

    private EContract findByDocumentId(String documentId) {
        return contractRepo.findByDocumentId(documentId)
                .orElseThrow(() -> new NotFoundException("Contract not found for documentId: " + documentId));
    }

    private UUID resolveInternalTenantId(UUID callerId) {
        UserResponse user = userGrpc.getUserIdAndRoleByKeyCloakId(callerId.toString());
        return UUID.fromString(user.getId());
    }
}
